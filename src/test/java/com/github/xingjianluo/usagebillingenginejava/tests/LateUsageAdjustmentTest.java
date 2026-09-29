package com.github.xingjianluo.usagebillingenginejava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.xingjianluo.usagebillingenginejava.domain.AdjustmentStatus;
import com.github.xingjianluo.usagebillingenginejava.domain.Invoice;
import com.github.xingjianluo.usagebillingenginejava.domain.InvoiceStatus;
import com.github.xingjianluo.usagebillingenginejava.domain.LedgerEntryType;
import com.github.xingjianluo.usagebillingenginejava.domain.Money;
import com.github.xingjianluo.usagebillingenginejava.domain.PaymentFailureReason;
import com.github.xingjianluo.usagebillingenginejava.repo.AdjustmentRepository;
import com.github.xingjianluo.usagebillingenginejava.service.AdjustmentService;
import com.github.xingjianluo.usagebillingenginejava.service.InvoiceService;
import com.github.xingjianluo.usagebillingenginejava.service.LedgerService;
import com.github.xingjianluo.usagebillingenginejava.service.PaymentService;
import com.github.xingjianluo.usagebillingenginejava.service.PeriodService;
import com.github.xingjianluo.usagebillingenginejava.service.SimulatedPaymentGateway;
import com.github.xingjianluo.usagebillingenginejava.service.SubscriptionService;
import com.github.xingjianluo.usagebillingenginejava.service.UsageIngestionService;
import com.github.xingjianluo.usagebillingenginejava.service.dto.AdjustmentResult;
import com.github.xingjianluo.usagebillingenginejava.tests.config.FixedClockConfig;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 结算后补报（迟到用量）补差：
 * 出账后补报触发补差、跨阶梯按整期重算、并发/重放幂等、多批累计与账本守恒、
 * 原账单不改写，并覆盖各支付/退款状态下的口径一致性。
 */
@SpringBootTest
@Import(FixedClockConfig.class)
class LateUsageAdjustmentTest {

    private static final Logger log = LoggerFactory.getLogger(LateUsageAdjustmentTest.class);

    @Autowired private SubscriptionService subscriptionService;
    @Autowired private UsageIngestionService ingestionService;
    @Autowired private InvoiceService invoiceService;
    @Autowired private PaymentService paymentService;
    @Autowired private AdjustmentService adjustmentService;
    @Autowired private LedgerService ledgerService;
    @Autowired private PeriodService periodService;
    @Autowired private SimulatedPaymentGateway gateway;
    @Autowired private AdjustmentRepository adjustmentRepository;

    private String subId;
    private LocalDate periodDate;

    @BeforeEach
    void setUp() {
        TestResetter.resetAll();
        subId = "sub-adj-" + System.nanoTime();
        subscriptionService.createSubscription(subId, "acct-" + subId, "BASIC",
                "2026-09-01T00:00:00Z");
        periodDate = LocalDate.of(2026, 9, 15);
    }

    private Invoice issueSeptember(String sid) {
        return invoiceService.getInvoice(invoiceService.issue(sid,
                periodService.periodOf(periodDate)).invoiceId());
    }

    @Test
    @DisplayName("出账后补报触发补差：delta=整期重算净增，原发票编号/明细/金额完全不改写")
    void lateUsageAfterInvoiceTriggersAdjustmentWithoutChangingInvoice() {
        ingestionService.ingest("e-early", subId, "api_calls", 600, "2026-09-05T08:00:00Z");
        Invoice before = issueSeptember(subId);
        assertEquals(Money.of("100.00"), before.total());
        int lineCountBefore = before.lines().size();

        ingestionService.ingest("e-late", subId, "api_calls", 900, "2026-09-10T08:00:00Z");
        AdjustmentResult r = adjustmentService.settle(subId,
                periodService.periodOf(periodDate), "batch-1");

        log.info("[关键输入] 出账时 600 次，出账后补报 900 次（累计 1500）");
        log.info("[判定依据] delta={}, 重算总额={}, 此前已计={}, created={}, 依据事件={}",
                r.delta(), r.recalculatedTotal(), r.previouslyBilled(), r.created(),
                r.basisEventIds());
        assertTrue(r.created());
        assertEquals(Money.of("10.00"), r.delta());
        assertEquals(Money.of("110.00"), r.recalculatedTotal());
        assertEquals(Money.of("100.00"), r.previouslyBilled());
        assertTrue(r.basisEventIds().containsAll(List.of("e-early", "e-late")),
                "补差依据必须说清用了哪些用量");

        Invoice after = invoiceService.getInvoice(before.id());
        assertEquals(before.id(), after.id());
        assertEquals(lineCountBefore, after.lines().size());
        assertEquals(Money.of("100.00"), after.total(), "原发票金额不改写");
    }

    @Test
    @DisplayName("补报把整期用量推进更高阶梯：补差按整期重算，而非只对新事件单独套价")
    void crossingTierRecalculatesWholePeriod() {
        // PRO 套餐 api_calls：5000 以内免费，5000 以上 0.01。
        String proSub = "sub-adj-pro-" + System.nanoTime();
        subscriptionService.createSubscription(proSub, "acct-pro", "PRO",
                "2026-09-01T00:00:00Z");
        ingestionService.ingest("pe-base", proSub, "api_calls", 1000, "2026-09-03T08:00:00Z");
        Invoice invoice = issueSeptember(proSub);
        assertEquals(Money.of("300.00"), invoice.total());

        // 补报 4500：整期累计 5500，跨过 5000 免费档。
        // 整期重算：(5500-5000)*0.01 = 5.00；只对新事件套价会错误得到 0（新 4500 仍在免费档）。
        ingestionService.ingest("pe-late", proSub, "api_calls", 4500, "2026-09-08T08:00:00Z");
        AdjustmentResult r = adjustmentService.settle(proSub,
                periodService.periodOf(periodDate), "batch-tier");
        log.info("[关键输入] PRO 出账时 1000（免费档内），出账后补报 4500，整期累计 5500 跨过 5000 档");
        log.info("[判定依据] delta={}, 重算总额={}, 此前已计={}（只对新事件套价会错误得到 0）",
                r.delta(), r.recalculatedTotal(), r.previouslyBilled());
        assertEquals(Money.of("5.00"), r.delta(), "跨高档必须按整期累计重算");
        assertEquals(Money.of("305.00"), r.recalculatedTotal());
        assertTrue(r.basisEventIds().containsAll(List.of("pe-base", "pe-late")));

        ingestionService.ingest("pe-late-2", proSub, "api_calls", 100, "2026-09-09T08:00:00Z");
        AdjustmentResult r2 = adjustmentService.settle(proSub,
                periodService.periodOf(periodDate), "batch-tier-2");
        assertEquals(Money.of("1.00"), r2.delta());
        assertEquals(Money.of("306.00"), r2.recalculatedTotal());
    }

    @Test
    @DisplayName("同一批输入重放：只产生一份补差单，delta 一致，第二次起 created=false，收款只入账一次")
    void sameBatchReplayIsIdempotent() {
        ingestionService.ingest("r-base", subId, "api_calls", 600, "2026-09-04T08:00:00Z");
        Invoice invoice = issueSeptember(subId);
        ingestionService.ingest("r-late", subId, "api_calls", 900, "2026-09-07T08:00:00Z");

        AdjustmentResult first = adjustmentService.settle(subId,
                periodService.periodOf(periodDate), "replay-batch");
        AdjustmentResult second = adjustmentService.settle(subId,
                periodService.periodOf(periodDate), "replay-batch");
        AdjustmentResult third = adjustmentService.settle(subId,
                periodService.periodOf(periodDate), "replay-batch");

        log.info("[关键输入] 同 batchId=replay-batch 连续结算 3 次");
        log.info("[判定依据] created: {}, {}, {}; id: {}/{}/{}",
                first.created(), second.created(), third.created(),
                first.adjustmentId(), second.adjustmentId(), third.adjustmentId());
        assertTrue(first.created());
        assertFalse(second.created());
        assertEquals(first.adjustmentId(), second.adjustmentId());
        assertEquals(first.adjustmentId(), third.adjustmentId());
        assertEquals(Money.of("10.00"), third.delta());

        assertTrue(adjustmentService.pay(first.adjustmentId()).success());
        adjustmentService.settle(subId, periodService.periodOf(periodDate), "replay-batch");
        assertEquals(Money.of("10.00"), ledgerService.sum(subId, LedgerEntryType.CHARGE),
                "同批重放不得重复入账");
        assertEquals(Money.of("100.00"), invoiceService.getInvoice(invoice.id()).total());
        assertEquals(1, adjustmentCount());
    }

    @Test
    @DisplayName("同一批输入并发触发：只产生一份补差单，所有线程看到同一 delta 与同一单号")
    void concurrentSettleOfSameBatchProducesSingleAdjustment() throws Exception {
        ingestionService.ingest("c-base", subId, "api_calls", 600, "2026-09-04T08:00:00Z");
        issueSeptember(subId);
        ingestionService.ingest("c-late", subId, "api_calls", 900, "2026-09-07T08:00:00Z");

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<AdjustmentResult> results = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                results.add(adjustmentService.settle(subId,
                        periodService.periodOf(periodDate), "concurrent-batch"));
            });
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        Set<String> ids = results.stream().map(AdjustmentResult::adjustmentId)
                .collect(Collectors.toSet());
        Set<String> deltas = results.stream().map(r -> r.delta().toString())
                .collect(Collectors.toSet());
        long createdCount = results.stream().filter(AdjustmentResult::created).count();
        log.info("[关键输入] {} 线程并发用同一 batchId 结算补差", threads);
        log.info("[判定依据] 结果数={}, 补差单数={}, created=true 次数={}, delta 集合={}",
                results.size(), ids.size(), createdCount, deltas);
        assertEquals(1, ids.size(), "并发只能产生一份补差单");
        assertEquals(1, createdCount, "只有一个线程真正创建");
        assertEquals(Set.of("10.00"), deltas, "所有线程看到完全一致的补差金额");
        assertEquals(1, adjustmentCount(), "仓储中只有一份补差单");
    }

    @Test
    @DisplayName("重算后总额无变化：delta 明确为 0，不产生补差单、不入账")
    void noNewUsageProducesZeroAdjustment() {
        ingestionService.ingest("z-base", subId, "api_calls", 600, "2026-09-04T08:00:00Z");
        Invoice invoice = issueSeptember(subId);

        AdjustmentResult noUsage = adjustmentService.settle(subId,
                periodService.periodOf(periodDate), "zero-1");
        assertFalse(noUsage.created());
        assertNull(noUsage.adjustmentId());
        assertEquals(Money.zero(), noUsage.delta());

        ingestionService.ingest("z-late", subId, "api_calls", 100, "2026-09-09T08:00:00Z");
        AdjustmentResult stillZero = adjustmentService.settle(subId,
                periodService.periodOf(periodDate), "zero-2");
        log.info("[关键输入] 出账 600，后补 100（累计 700 仍在 BASIC 免费档）");
        log.info("[判定依据] delta={}, created={}, 原因={}",
                stillZero.delta(), stillZero.created(), stillZero.reason());
        assertFalse(stillZero.created());
        assertEquals(Money.zero(), stillZero.delta());
        assertEquals(Money.of("100.00"), stillZero.recalculatedTotal());
        assertEquals(Money.of("100.00"), invoiceService.getInvoice(invoice.id()).total());
        assertEquals(Money.zero(), ledgerService.sum(subId, LedgerEntryType.CHARGE),
                "补差为零且原发票未支付，账本无分录");
        assertEquals(0, adjustmentCount());
    }

    @Test
    @DisplayName("连续多批补差：Σ补差 + 原发票净额 = 最终全量重算应计总额，收款后账本守恒")
    void multipleBatchesTelescopeAndLedgerConserved() {
        var period = periodService.periodOf(periodDate);
        ingestionService.ingest("m-base", subId, "api_calls", 1500, "2026-09-02T08:00:00Z");
        Invoice invoice = issueSeptember(subId);
        Money originalNet = invoice.total();
        assertTrue(paymentService.pay(invoice.id()).success());

        ingestionService.ingest("m-late-1", subId, "api_calls", 4000, "2026-09-06T08:00:00Z");
        AdjustmentResult a1 = adjustmentService.settle(subId, period, "mb-1");
        assertEquals(Money.of("80.00"), a1.delta());
        assertTrue(adjustmentService.pay(a1.adjustmentId()).success());

        ingestionService.ingest("m-late-2", subId, "api_calls", 500, "2026-09-07T08:00:00Z");
        AdjustmentResult a2 = adjustmentService.settle(subId, period, "mb-2");
        assertEquals(Money.of("10.00"), a2.delta());
        assertEquals(Money.of("200.00"), a2.recalculatedTotal());
        assertEquals(Money.of("190.00"), a2.previouslyBilled());
        assertTrue(adjustmentService.pay(a2.adjustmentId()).success());

        // 同输入重放（即便上一笔已收款）：回读同一笔、金额/单号一致，不再落第二笔、不重复收款。
        AdjustmentResult a3 = adjustmentService.settle(subId, period, "mb-3");
        assertFalse(a3.created());
        assertEquals(a2.adjustmentId(), a3.adjustmentId(),
                "同一批输入重放必须回读既有补差单，不能因已收款而返回空单号/0");
        assertEquals(Money.of("10.00"), a3.delta(),
                "同一批输入重放返回的补差金额必须与首次一致、可复现");
        assertEquals(AdjustmentStatus.PAID,
                adjustmentService.getAdjustment(a3.adjustmentId()).status());

        Money telescoped = originalNet.plus(a1.delta()).plus(a2.delta());
        log.info("[关键输入] 原 {} + 补差 {} + 补差 {} = {}，最终重算 {}",
                originalNet, a1.delta(), a2.delta(), telescoped, a2.recalculatedTotal());
        assertEquals(a2.recalculatedTotal(), telescoped,
                "原账单 + 全部补差必须等于最终应计总额");

        Money charge = ledgerService.sum(subId, LedgerEntryType.CHARGE);
        Money credit = ledgerService.sum(subId, LedgerEntryType.CREDIT);
        Money refund = ledgerService.sum(subId,
                com.github.xingjianluo.usagebillingenginejava.domain.LedgerEntryType.REFUND);
        log.info("[判定依据] CHARGE={}, CREDIT={}, REFUND={}, 实收净额={}",
                charge, credit, refund, charge.minus(credit).minus(refund));
        assertEquals(Money.of("200.00"), charge);
        assertEquals(Money.zero(), credit);
        assertEquals(Money.zero(), refund);
        // 账期未作废（无退款）时 CHARGE 即应收/实收总额；最终全量重算应计=200，与 CHARGE 相等。
        assertEquals(a2.recalculatedTotal(), charge,
                "全部补差收款后，账本 CHARGE 等于最终全量重算应计总额（望远镜守恒）");
        assertEquals(2, adjustmentCount());
    }

    @Test
    @DisplayName("已支付账期补收：补差独立收款，原支付不重复扣款；补差失败可重试且失败不入账")
    void paidPeriodAdjustmentCollectedOnceAndRetriable() {
        var period = periodService.periodOf(periodDate);
        ingestionService.ingest("p-base", subId, "api_calls", 600, "2026-09-04T08:00:00Z");
        Invoice invoice = issueSeptember(subId);
        assertTrue(paymentService.pay(invoice.id()).success());

        ingestionService.ingest("p-late", subId, "api_calls", 900, "2026-09-08T08:00:00Z");
        AdjustmentResult a = adjustmentService.settle(subId, period, "paid-batch");
        assertEquals(InvoiceStatus.PAID, invoiceService.getInvoice(invoice.id()).status());
        assertEquals(Money.of("100.00"), invoiceService.getInvoice(invoice.id()).total());
        assertEquals(AdjustmentStatus.OPEN,
                adjustmentService.getAdjustment(a.adjustmentId()).status());

        gateway.setScript(subId, List.of(PaymentFailureReason.INSUFFICIENT_FUNDS,
                PaymentFailureReason.CARD_DECLINED));
        assertEquals(AdjustmentStatus.PAYMENT_FAILED,
                adjustmentService.pay(a.adjustmentId()).status());
        assertEquals(AdjustmentStatus.PAYMENT_FAILED,
                adjustmentService.retry(a.adjustmentId()).status());
        assertEquals(Money.of("100.00"), ledgerService.sum(subId, LedgerEntryType.CHARGE),
                "补差失败不得入账，原发票 CHARGE 仍为 100");
        var ok = adjustmentService.retry(a.adjustmentId());
        assertTrue(ok.success());
        assertEquals(AdjustmentStatus.PAID, ok.status());
        assertEquals(Money.of("110.00"), ledgerService.sum(subId, LedgerEntryType.CHARGE),
                "补差成功后累计 CHARGE=100(原)+10(补差)，原支付未重复扣");
        adjustmentService.pay(a.adjustmentId());
        adjustmentService.retry(a.adjustmentId());
        assertEquals(Money.of("110.00"), ledgerService.sum(subId, LedgerEntryType.CHARGE),
                "终态重复调用不重复入账");
    }

    @Test
    @DisplayName("支付失败终态账期也能补报补差：不静默丢单，补差独立单据可收款入账")
    void failedPeriodStillAcceptsLateUsage() {
        var period = periodService.periodOf(periodDate);
        ingestionService.ingest("f-base", subId, "api_calls", 600, "2026-09-04T08:00:00Z");
        Invoice invoice = issueSeptember(subId);
        gateway.setScript(subId, List.of(PaymentFailureReason.GATEWAY_TIMEOUT,
                PaymentFailureReason.GATEWAY_TIMEOUT, PaymentFailureReason.INVALID_ACCOUNT));
        paymentService.pay(invoice.id());
        paymentService.retry(invoice.id());
        var failed = paymentService.retry(invoice.id());
        assertEquals(InvoiceStatus.MAX_RETRIES_EXCEEDED, failed.invoiceStatus());

        ingestionService.ingest("f-late", subId, "api_calls", 900, "2026-09-08T08:00:00Z");
        AdjustmentResult a = adjustmentService.settle(subId, period, "failed-period-batch");
        log.info("[关键输入] 原发票三次支付失败后补报迟到用量");
        log.info("[判定依据] 补差单已生成 delta={}, 原发票状态={}（未丢单）",
                a.delta(), invoiceService.getInvoice(invoice.id()).status());
        assertTrue(a.created());
        assertEquals(Money.of("10.00"), a.delta());
        assertTrue(adjustmentService.pay(a.adjustmentId()).success());
        assertEquals(Money.of("10.00"), ledgerService.sum(subId, LedgerEntryType.CHARGE),
                "原发票失败未入账，只有补差 10.00 入账，不漏计不重复");
    }

    @Test
    @DisplayName("已支付发票退款作废时联动退回已收款补差，账期关闭后账本仍守恒")
    void invoiceRefundAlsoRefundsPaidAdjustments() {
        var period = periodService.periodOf(periodDate);
        ingestionService.ingest("v-base", subId, "api_calls", 600, "2026-09-04T08:00:00Z");
        Invoice invoice = issueSeptember(subId);
        paymentService.pay(invoice.id());
        ingestionService.ingest("v-late", subId, "api_calls", 900, "2026-09-08T08:00:00Z");
        AdjustmentResult a = adjustmentService.settle(subId, period, "void-batch");
        adjustmentService.pay(a.adjustmentId());
        assertEquals(Money.of("110.00"), ledgerService.sum(subId, LedgerEntryType.CHARGE));

        var refunded = paymentService.refund(invoice.id());
        assertTrue(refunded.success());
        Money charge = ledgerService.sum(subId, LedgerEntryType.CHARGE);
        Money credit = ledgerService.sum(subId, LedgerEntryType.CREDIT);
        Money refund = ledgerService.sum(subId,
                com.github.xingjianluo.usagebillingenginejava.domain.LedgerEntryType.REFUND);
        log.info("[判定依据] 作废后 CHARGE={}, CREDIT={}, REFUND={}, 守恒={}",
                charge, credit, refund, ledgerService.isConserved(subId));
        assertEquals(Money.of("110.00"), charge);
        assertEquals(Money.of("110.00"), refund, "发票退款 100 + 补差退款 10");
        assertEquals(AdjustmentStatus.REFUNDED,
                adjustmentService.getAdjustment(a.adjustmentId()).status());
        assertTrue(ledgerService.isConserved(subId), "账期关闭后 CHARGE = CREDIT + REFUND");
    }

    @Test
    @DisplayName("未出账的账期触发补差：明确报冲突（409），不悄悄开票或丢单")
    void settleBeforeInvoiceIsRejected() {
        ingestionService.ingest("n-base", subId, "api_calls", 600, "2026-09-04T08:00:00Z");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> adjustmentService.settle(subId, periodService.periodOf(periodDate), "x"));
        log.info("[判定依据] 未出账补差被拒绝: {}", ex.getMessage());
        assertTrue(ex.getMessage().contains("尚未出账"));
    }

    @Test
    @DisplayName("账期内多次变更且变更点落在一天中间：补差整期重算与正常出账同口径")
    void midDayAndMultiplePlanChangesUseSameRecalcAsIssue() {
        var period = periodService.periodOf(periodDate);
        // BASIC -> PRO @ 9/11 12:00（一天中间）-> BASIC @ 9/21 00:00。
        subscriptionService.changePlan(subId, "PRO", "2026-09-11T12:00:00Z");
        subscriptionService.changePlan(subId, "BASIC", "2026-09-21T00:00:00Z");
        ingestionService.ingest("chg-early", subId, "api_calls", 100, "2026-09-05T08:00:00Z");
        Invoice invoice = issueSeptember(subId);
        Money issuedTotal = invoice.total();

        // 迟到用量：一条落在 PRO 片（9/15），一条落在第二个 BASIC 片（9/25）。
        ingestionService.ingest("chg-late-pro", subId, "api_calls", 6000,
                "2026-09-15T09:00:00Z");
        ingestionService.ingest("chg-late-basic", subId, "api_calls", 2000,
                "2026-09-25T09:00:00Z");

        var subscription = getSubscription();
        var expectedCalc = billingCalculator.calculate(subscription, period);
        AdjustmentResult a = adjustmentService.settle(subId, period, "chg-batch");
        log.info("[关键输入] 变更点 9/11 12:00（一天中间）与 9/21 00:00；迟到量 PRO片6000、BASIC片2000");
        log.info("[判定依据] 原发票净额={}, 计算器整期重算={}, 补差 delta={}, 补差重算总额={}",
                issuedTotal, expectedCalc.netTotal(), a.delta(), a.recalculatedTotal());
        assertEquals(expectedCalc.netTotal(), a.recalculatedTotal(),
                "补差重算总额必须与 BillingCalculator 正常出账口径一致");
        assertEquals(expectedCalc.netTotal().minus(issuedTotal), a.delta());
        // PRO 片内迟到 6000 次：(6000-5000)*0.01 = 10.00；
        // 9/21 00:00 变更点落在按天边界上，末个 BASIC 片天数为 0，基础费两期前后不变，
        // 故整期净增恰为 10.00。该用例的关键保证是上面“与计算器同口径”的恒等。
        assertEquals(Money.of("10.00"), a.delta());
    }

    @Autowired
    private com.github.xingjianluo.usagebillingenginejava.service.BillingCalculator billingCalculator;
    @Autowired
    private com.github.xingjianluo.usagebillingenginejava.repo.SubscriptionRepository
            subscriptionRepositoryForCalc;

    private com.github.xingjianluo.usagebillingenginejava.domain.Subscription getSubscription() {
        return subscriptionRepositoryForCalc.findById(subId).orElseThrow();
    }

    private int adjustmentCount() {
        return adjustmentRepository.findBySubscriptionAndPeriod(subId,
                periodService.periodOf(periodDate)).size();
    }
}
