package com.github.xingjianluo.usagebillingenginejava.tests;

import com.github.xingjianluo.usagebillingenginejava.billing.BillingService;
import com.github.xingjianluo.usagebillingenginejava.invoicing.InvoiceService;
import com.github.xingjianluo.usagebillingenginejava.ledger.LedgerService;
import com.github.xingjianluo.usagebillingenginejava.metering.TierRatingService;
import com.github.xingjianluo.usagebillingenginejava.metering.UsageService;
import com.github.xingjianluo.usagebillingenginejava.model.Invoice;
import com.github.xingjianluo.usagebillingenginejava.model.InvoiceStatus;
import com.github.xingjianluo.usagebillingenginejava.model.LedgerEntry;
import com.github.xingjianluo.usagebillingenginejava.model.Money;
import com.github.xingjianluo.usagebillingenginejava.model.PaymentFailureReason;
import com.github.xingjianluo.usagebillingenginejava.model.PaymentAttempt;
import com.github.xingjianluo.usagebillingenginejava.payment.LocalPaymentGateway;
import com.github.xingjianluo.usagebillingenginejava.payment.RetryPolicy;
import com.github.xingjianluo.usagebillingenginejava.pricing.PlanCatalog;
import com.github.xingjianluo.usagebillingenginejava.store.InMemoryStore;
import com.github.xingjianluo.usagebillingenginejava.subscription.SubscriptionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 发票与支付测试：
 * - 并发/重试开票只产生一张发票；
 * - 支付失败原因可区分，按策略有限重试；
 * - 成功仅一条收入流水；终态后重试/支付被拒绝，不丢失也不重复入账；
 * - 退款后净额守恒。
 */
class InvoicePaymentTest {

    private static final Logger log = LoggerFactory.getLogger(InvoicePaymentTest.class);

    static class Harness {
        final InMemoryStore store = new InMemoryStore();
        final PlanCatalog catalog = new PlanCatalog();
        final SubscriptionService subscriptionService = new SubscriptionService(store, catalog);
        final UsageService usageService = new UsageService(store);
        final TierRatingService tierRatingService = new TierRatingService();
        final BillingService billingService = new BillingService(
                store, subscriptionService, usageService, tierRatingService, catalog);
        final LocalPaymentGateway gateway = new LocalPaymentGateway();
        final RetryPolicy retryPolicy = new RetryPolicy();
        final LedgerService ledgerService = new LedgerService(store);
        final InvoiceService invoiceService = new InvoiceService(
                store, billingService, subscriptionService, gateway, retryPolicy, ledgerService);

        Invoice createSubscriptionAndIssue(String account, Instant anchor) {
            subscriptionService.create(account, anchor, PlanCatalog.PRO);
            return invoiceService.issue(account, anchor);
        }
    }

    @Test
    @DisplayName("并发开票：20 线程同时开票，只产生一张发票且金额一致")
    void concurrentIssueProducesSingleInvoice() throws Exception {
        Harness h = new Harness();
        Instant anchor = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(600);
        // 订阅先行建好；线程中的 create 只是并发的幂等重复调用
        h.subscriptionService.create("acct-c", anchor, PlanCatalog.PRO);

        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<Invoice> results = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    // 部分线程先重复 create（幂等），所有线程都走到 issue —— 混合并发入口
                    if (Math.random() < 0.5) {
                        h.subscriptionService.create("acct-c", anchor, PlanCatalog.PRO);
                    }
                    results.add(h.invoiceService.issue("acct-c", anchor));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        Set<String> ids = new HashSet<>();
        Set<Money> amounts = new HashSet<>();
        results.forEach(inv -> { ids.add(inv.invoiceId()); amounts.add(inv.amount()); });
        log.info("[关键输入] 20 线程并发开票 [判定依据] 账号锁内检查-创建，发票 id 集合={} 张数={} 金额种类={}",
                ids, results.size(), amounts);

        assertEquals(1, ids.size(), "同一周期只允许一张发票");
        assertEquals(threads, results.size());
        assertEquals(1, amounts.size());
        assertEquals(1, h.store.allInvoices().size(), "存储中也只能有一张发票");
    }

    @Test
    @DisplayName("支付失败原因可区分且按策略重试：前两次不同原因失败，第三次成功，仅一条入账")
    void retryThenSuccessSingleLedgerEntry() {
        Harness h = new Harness();
        Instant anchor = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(600);
        Invoice invoice = h.createSubscriptionAndIssue("acct-retry", anchor);

        h.gateway.script("acct-retry",
                PaymentFailureReason.INSUFFICIENT_FUNDS,
                PaymentFailureReason.GATEWAY_ERROR,
                PaymentFailureReason.NONE);

        Invoice a1 = h.invoiceService.pay(invoice.invoiceId());
        Invoice a2 = h.invoiceService.pay(invoice.invoiceId());
        Invoice a3 = h.invoiceService.pay(invoice.invoiceId());

        List<PaymentAttempt> attempts = a3.attempts();
        log.info("[关键输入] 失败脚本 [余额不足, 通道错误, 成功] [判定依据] attempts={} -> 状态={}",
                attempts.stream().map(a -> (a.success() ? "OK" : a.failureReason())).toList(),
                a3.status());

        assertEquals(InvoiceStatus.PENDING_PAYMENT, a1.status());
        assertEquals(PaymentFailureReason.INSUFFICIENT_FUNDS, a1.lastFailureReason());
        assertEquals(InvoiceStatus.PENDING_PAYMENT, a2.status());
        assertEquals(PaymentFailureReason.GATEWAY_ERROR, a2.lastFailureReason());
        assertEquals(InvoiceStatus.PAID, a3.status());
        assertEquals(3, a3.attemptCount());

        List<LedgerEntry> entries = h.ledgerService.byInvoice(invoice.invoiceId());
        long payments = entries.stream().filter(e -> "PAYMENT".equals(e.type())).count();
        log.info("[判定依据] PAYMENT 流水数={} 净额={}（必须唯一且等于发票额）", payments,
                h.ledgerService.netOf(invoice.invoiceId()));
        assertEquals(1, payments, "成功重试不得重复入账");
        assertEquals(invoice.amount(), h.ledgerService.netOf(invoice.invoiceId()));

        // 已支付再支付：必须被拒绝
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> h.invoiceService.pay(invoice.invoiceId()));
        log.warn("[终态保护] 重复支付被拒绝: {}", ex.getMessage());
        assertTrue(ex.getMessage().contains("已支付"));
        assertEquals(1, h.ledgerService.byInvoice(invoice.invoiceId()).size());
    }

    @Test
    @DisplayName("始终失败：达到最大尝试次数进入 FAILED 终态，之后重试被拒绝且不入账")
    void permanentFailureReachesTerminalState() {
        Harness h = new Harness();
        Instant anchor = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(600);
        Invoice invoice = h.createSubscriptionAndIssue("acct-fail", anchor);
        h.gateway.script("acct-fail", PaymentFailureReason.CARD_DECLINED);

        Invoice terminal = h.invoiceService.runRetries(invoice.invoiceId());

        log.info("[关键输入] 失败脚本 [卡被拒(持续)] [判定依据] 自动重试至 {} 次 -> {}，最后原因={}",
                terminal.attemptCount(), terminal.status(), terminal.lastFailureReason());
        assertEquals(InvoiceStatus.FAILED, terminal.status());
        assertEquals(RetryPolicy.MAX_ATTEMPTS, terminal.attemptCount());
        assertEquals(PaymentFailureReason.CARD_DECLINED, terminal.lastFailureReason());
        assertEquals(Money.ZERO, h.ledgerService.netOf(invoice.invoiceId()), "失败不得入账");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> h.invoiceService.pay(invoice.invoiceId()));
        assertTrue(ex.getMessage().contains("失败终态"));
        IllegalStateException ex2 = assertThrows(IllegalStateException.class,
                () -> h.invoiceService.runRetries(invoice.invoiceId()));
        assertTrue(ex2.getMessage().contains("失败终态") || ex2.getMessage().contains("人工处理"));
        log.warn("[终态保护] FAILED 后 pay/retry 均被拒绝（{}），发票保留可查，金额={} 未丢失",
                ex2.getMessage(), invoice.amount());
    }

    @Test
    @DisplayName("账本守恒：支付成功后部分退款，收入与退款代数和等于实付净额")
    void refundConservation() {
        Harness h = new Harness();
        Instant anchor = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(600);
        Invoice invoice = h.createSubscriptionAndIssue("acct-refund", anchor);
        Invoice paid = h.invoiceService.pay(invoice.invoiceId());

        Money refund = Money.of(30);
        h.ledgerService.recordRefund(paid, refund, "客户投诉补偿");

        Money net = h.ledgerService.netOf(invoice.invoiceId());
        log.info("[关键输入] 发票额 {} 退款 {} [判定依据] 净额 = 收入 + 负退款 = {}",
                invoice.amount(), refund, net);
        assertEquals(invoice.amount().subtract(refund), net);

        // 超额退款必须被拒绝
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                h.ledgerService.recordRefund(paid, invoice.amount(), "超额退款"));
        log.warn("[守恒保护] 超额退款被拒绝: {}", ex.getMessage());
    }

    @Test
    @DisplayName("终态幂等：PAID 后再次 runRetries 被显式拒绝，不新增尝试与流水")
    void runRetriesRejectedAfterPaid() {
        Harness h = new Harness();
        Instant anchor = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(600);
        Invoice invoice = h.createSubscriptionAndIssue("acct-ok", anchor);

        Invoice r1 = h.invoiceService.runRetries(invoice.invoiceId()); // 通道默认成功
        // 第二次进入：已是 PAID，必须显式拒绝；不新增尝试与流水
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> h.invoiceService.runRetries(invoice.invoiceId()));

        assertEquals(InvoiceStatus.PAID, r1.status());
        assertTrue(ex.getMessage().contains("已支付"));
        Invoice stored = h.store.getInvoiceById(invoice.invoiceId());
        assertEquals(1, stored.attemptCount());
        assertEquals(1, h.ledgerService.byInvoice(invoice.invoiceId()).size());
        log.info("[关键输入] 默认通道成功后重复 runRetries 被拒绝({}) [判定依据] attempts={} 流水=1 状态保持 PAID",
                ex.getMessage(), stored.attemptCount());
    }
}
