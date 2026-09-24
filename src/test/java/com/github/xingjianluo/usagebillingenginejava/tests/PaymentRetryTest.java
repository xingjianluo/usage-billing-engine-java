package com.github.xingjianluo.usagebillingenginejava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.xingjianluo.usagebillingenginejava.domain.Invoice;
import com.github.xingjianluo.usagebillingenginejava.domain.InvoiceStatus;
import com.github.xingjianluo.usagebillingenginejava.domain.LedgerEntryType;
import com.github.xingjianluo.usagebillingenginejava.domain.Money;
import com.github.xingjianluo.usagebillingenginejava.domain.PaymentFailureReason;
import com.github.xingjianluo.usagebillingenginejava.service.InvoiceService;
import com.github.xingjianluo.usagebillingenginejava.service.LedgerService;
import com.github.xingjianluo.usagebillingenginejava.service.PaymentService;
import com.github.xingjianluo.usagebillingenginejava.service.PeriodService;
import com.github.xingjianluo.usagebillingenginejava.service.SimulatedPaymentGateway;
import com.github.xingjianluo.usagebillingenginejava.service.SubscriptionService;
import com.github.xingjianluo.usagebillingenginejava.service.dto.PaymentResult;
import com.github.xingjianluo.usagebillingenginejava.tests.config.FixedClockConfig;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 支付失败与重试：失败原因可区分、状态机正确、终态不丢失不重复入账、支付幂等。
 */
@SpringBootTest
@Import(FixedClockConfig.class)
class PaymentRetryTest {

    private static final Logger log = LoggerFactory.getLogger(PaymentRetryTest.class);

    @Autowired private SubscriptionService subscriptionService;
    @Autowired private InvoiceService invoiceService;
    @Autowired private PaymentService paymentService;
    @Autowired private LedgerService ledgerService;
    @Autowired private PeriodService periodService;
    @Autowired private SimulatedPaymentGateway gateway;

    private String subId;
    private String invoiceId;

    @BeforeEach
    void setUp() {
        TestResetter.resetAll();
        subId = "sub-pay-" + System.nanoTime();
        subscriptionService.createSubscription(subId, subId, "BASIC",
                "2026-09-01T00:00:00Z");
        var period = periodService.periodOf(LocalDate.of(2026, 9, 15));
        invoiceId = invoiceService.issue(subId, period).invoiceId();
    }

    @Test
    @DisplayName("首次余额不足、第二次卡过期、第三次成功：原因可区分，仅成功时入账一次")
    void failTwiceWithDistinctReasonsThenSucceed() {
        gateway.setScript(subId, List.of(
                PaymentFailureReason.INSUFFICIENT_FUNDS,
                PaymentFailureReason.CARD_DECLINED));

        PaymentResult r1 = paymentService.pay(invoiceId);
        log.info("[判定依据] 第1次 pay: success={}, reason={}, status={}, attempts={}",
                r1.success(), r1.failureReason(), r1.invoiceStatus(), r1.attemptCount());
        assertFalse(r1.success());
        assertEquals(PaymentFailureReason.INSUFFICIENT_FUNDS, r1.failureReason());
        assertEquals(InvoiceStatus.PAYMENT_FAILED, r1.invoiceStatus());

        // 对 PAYMENT_FAILED 再调 pay 入口：不允许，必须走 retry（不会重复扣款尝试）
        PaymentResult wrongEntry = paymentService.pay(invoiceId);
        assertEquals(InvoiceStatus.PAYMENT_FAILED, wrongEntry.invoiceStatus());
        assertEquals(1, wrongEntry.attemptCount(), "错误入口不得产生新的扣款尝试");

        PaymentResult r2 = paymentService.retry(invoiceId);
        log.info("[判定依据] 第2次 retry: success={}, reason={}, status={}, attempts={}",
                r2.success(), r2.failureReason(), r2.invoiceStatus(), r2.attemptCount());
        assertFalse(r2.success());
        assertEquals(PaymentFailureReason.CARD_DECLINED, r2.failureReason());
        assertEquals(InvoiceStatus.PAYMENT_FAILED, r2.invoiceStatus());
        assertEquals(2, r2.attemptCount());

        PaymentResult r3 = paymentService.retry(invoiceId);
        log.info("[判定依据] 第3次 retry: success={}, status={}, attempts={}, CHARGE={}, CREDIT={}",
                r3.success(), r3.invoiceStatus(), r3.attemptCount(), r3.charge(), r3.credit());
        assertTrue(r3.success());
        assertEquals(InvoiceStatus.PAID, r3.invoiceStatus());
        assertEquals(3, r3.attemptCount());
        assertEquals(Money.of("100.00"), r3.charge());

        // 失败期间不得有任何账本分录（第三次成功前 CHARGE 为 0，下面成功后变为 100）

        // PAID 后重复 pay/retry：幂等，不重复扣款、不重复入账
        PaymentResult replay1 = paymentService.pay(invoiceId);
        PaymentResult replay2 = paymentService.retry(invoiceId);
        assertTrue(replay1.success() && replay2.success());
        assertEquals(3, invoiceService.getInvoice(invoiceId).attemptCount(),
                "终态上的重复调用不得增加尝试次数");
        assertEquals(Money.of("100.00"),
                ledgerService.sum(subId, LedgerEntryType.CHARGE), "只入账一次");
    }

    @Test
    @DisplayName("三次均失败：进入 MAX_RETRIES_EXCEEDED 终态，原因保留，不再扣款入账")
    void allAttemptsFailReachesTerminalState() {
        gateway.setScript(subId, List.of(
                PaymentFailureReason.GATEWAY_TIMEOUT,
                PaymentFailureReason.GATEWAY_TIMEOUT,
                PaymentFailureReason.INVALID_ACCOUNT));

        PaymentResult r1 = paymentService.pay(invoiceId);
        PaymentResult r2 = paymentService.retry(invoiceId);
        PaymentResult r3 = paymentService.retry(invoiceId);
        log.info("[关键输入] 通道脚本: TIMEOUT, TIMEOUT, INVALID_ACCOUNT");
        log.info("[判定依据] 状态序列: {} -> {} -> {}, 最后原因={}",
                r1.invoiceStatus(), r2.invoiceStatus(), r3.invoiceStatus(), r3.failureReason());

        assertFalse(r3.success());
        assertEquals(InvoiceStatus.MAX_RETRIES_EXCEEDED, r3.invoiceStatus());
        assertEquals(PaymentFailureReason.INVALID_ACCOUNT, r3.failureReason(),
                "终态必须保留最后一次可区分原因，不静默丢失");
        assertEquals(3, r3.attemptCount());
        assertEquals(Money.zero(), ledgerService.sum(subId, LedgerEntryType.CHARGE),
                "全部失败不得入账");

        // 终态上继续 retry/pay：直接返回，不新增尝试、不扣款
        PaymentResult after = paymentService.retry(invoiceId);
        paymentService.pay(invoiceId);
        assertEquals(InvoiceStatus.MAX_RETRIES_EXCEEDED, after.invoiceStatus());
        assertEquals(3, after.attemptCount());
    }

    @Test
    @DisplayName("对同一张发票并发支付：通道只扣款一次，账本只入账一次")
    void concurrentPayIsIdempotent() throws Exception {
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<PaymentResult> results = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                results.add(paymentService.pay(invoiceId));
            });
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        long successes = results.stream().filter(PaymentResult::success).count();
        log.info("[关键输入] {} 线程并发 pay 同一发票 {}", threads, invoiceId);
        log.info("[判定依据] 成功返回数={}, attemptCount={}, CHARGE 合计={}",
                successes, invoiceService.getInvoice(invoiceId).attemptCount(),
                ledgerService.sum(subId, LedgerEntryType.CHARGE));

        assertEquals(threads, successes, "所有线程都得到成功结论（同一笔支付）");
        assertEquals(1, invoiceService.getInvoice(invoiceId).attemptCount(),
                "通道只真正扣款一次");
        assertEquals(Money.of("100.00"),
                ledgerService.sum(subId, LedgerEntryType.CHARGE), "账本只入账一次");
        assertEquals(InvoiceStatus.PAID, invoiceService.getInvoice(invoiceId).status());
    }
}
