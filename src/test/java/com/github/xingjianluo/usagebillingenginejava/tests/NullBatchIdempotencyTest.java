package com.github.xingjianluo.usagebillingenginejava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.xingjianluo.usagebillingenginejava.domain.Invoice;
import com.github.xingjianluo.usagebillingenginejava.domain.LedgerEntryType;
import com.github.xingjianluo.usagebillingenginejava.domain.AdjustmentStatus;
import com.github.xingjianluo.usagebillingenginejava.domain.Money;
import com.github.xingjianluo.usagebillingenginejava.domain.PaymentFailureReason;
import com.github.xingjianluo.usagebillingenginejava.service.SimulatedPaymentGateway;
import java.util.List;
import com.github.xingjianluo.usagebillingenginejava.service.PaymentService;
import com.github.xingjianluo.usagebillingenginejava.repo.AdjustmentRepository;
import com.github.xingjianluo.usagebillingenginejava.service.AdjustmentService;
import com.github.xingjianluo.usagebillingenginejava.service.InvoiceService;
import com.github.xingjianluo.usagebillingenginejava.service.LedgerService;
import com.github.xingjianluo.usagebillingenginejava.service.PeriodService;
import com.github.xingjianluo.usagebillingenginejava.service.SubscriptionService;
import com.github.xingjianluo.usagebillingenginejava.service.UsageIngestionService;
import com.github.xingjianluo.usagebillingenginejava.service.dto.AdjustmentResult;
import com.github.xingjianluo.usagebillingenginejava.tests.config.FixedClockConfig;
import java.time.LocalDate;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 调用方不提供可选 batchId 时，补差仍必须对“同一批输入”严格幂等、可复现：
 * 重放/并发/重试拿到同一个补差单号与同一笔金额，绝不能把已产生、未收款的补差藏成 0。
 */
@SpringBootTest
@Import(FixedClockConfig.class)
class NullBatchIdempotencyTest {

    @Autowired private SubscriptionService subscriptionService;
    @Autowired private UsageIngestionService ingestionService;
    @Autowired private InvoiceService invoiceService;
    @Autowired private AdjustmentService adjustmentService;
    @Autowired private PeriodService periodService;
    @Autowired private PaymentService paymentService;
    @Autowired private LedgerService ledgerService;
    @Autowired private AdjustmentRepository adjustmentRepository;
    @Autowired private SimulatedPaymentGateway gateway;

    private String subId;
    private LocalDate periodDate;

    @BeforeEach
    void setUp() {
        TestResetter.resetAll();
        subId = "sub-nobatch-" + System.nanoTime();
        subscriptionService.createSubscription(subId, "acct-" + subId, "BASIC",
                "2026-09-01T00:00:00Z");
        periodDate = LocalDate.of(2026, 9, 15);
    }

    @Test
    @DisplayName("不带 batchId：同一批输入重放返回同一补差单与同一金额，第二次不是 0/空单号")
    void replayWithoutBatchIdReturnsSameAdjustment() {
        ingestionService.ingest("nb-base", subId, "api_calls", 600, "2026-09-04T08:00:00Z");
        invoiceService.issue(subId, periodService.periodOf(periodDate));
        ingestionService.ingest("nb-late", subId, "api_calls", 900, "2026-09-07T08:00:00Z");

        AdjustmentResult first = adjustmentService.settle(subId,
                periodService.periodOf(periodDate), null);
        AdjustmentResult second = adjustmentService.settle(subId,
                periodService.periodOf(periodDate), null);

        assertTrue(first.created());
        assertEquals(Money.of("10.00"), first.delta());
        assertNotNull(first.adjustmentId());
        assertFalse(second.created(), "重放不应再产生第二笔补差");
        assertEquals(first.adjustmentId(), second.adjustmentId(),
                "重放必须回读同一笔补差单，不能返回空单号");
        assertEquals(Money.of("10.00"), second.delta(),
                "重放必须返回同一笔补差金额，不能藏成 0");
        assertEquals(1, adjustmentRepository.findBySubscriptionAndPeriod(subId,
                periodService.periodOf(periodDate)).size());
    }

    @Test
    @DisplayName("不带 batchId：同一批输入并发触发只落一笔，所有线程同单号同金额，无 0")
    void concurrentWithoutBatchIdProducesSingleAdjustment() throws Exception {
        ingestionService.ingest("nc-base", subId, "api_calls", 600, "2026-09-04T08:00:00Z");
        invoiceService.issue(subId, periodService.periodOf(periodDate));
        ingestionService.ingest("nc-late", subId, "api_calls", 900, "2026-09-07T08:00:00Z");

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
                        periodService.periodOf(periodDate), null));
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
        assertEquals(1, ids.size(), "无 batchId 并发也只能产生一笔补差");
        assertEquals(1, createdCount);
        assertEquals(Set.of("10.00"), deltas, "任何线程都不能拿到 0");
        assertEquals(1, adjustmentRepository.findBySubscriptionAndPeriod(subId,
                periodService.periodOf(periodDate)).size());
    }

    @Test
    @DisplayName("不带 batchId：整期确无净增时 delta=0 且不落单，重放始终为 0")
    void genuinelyZeroDeltaCreatesNothing() {
        ingestionService.ingest("nz-base", subId, "api_calls", 600, "2026-09-04T08:00:00Z");
        invoiceService.issue(subId, periodService.periodOf(periodDate));

        // 无任何迟到用量即结算：整期重算 == 原发票净额。
        AdjustmentResult r1 = adjustmentService.settle(subId,
                periodService.periodOf(periodDate), null);
        assertEquals(Money.zero(), r1.delta());
        assertFalse(r1.created());
        assertNull(r1.adjustmentId());

        // 补报 100 次仍在 BASIC 免费档（1000 以内），整期应计仍 100.00。
        ingestionService.ingest("nz-late", subId, "api_calls", 100, "2026-09-09T08:00:00Z");
        AdjustmentResult r2 = adjustmentService.settle(subId,
                periodService.periodOf(periodDate), null);
        assertEquals(Money.zero(), r2.delta());
        assertNull(r2.adjustmentId());

        // 再重放一次，结论不变。
        AdjustmentResult r3 = adjustmentService.settle(subId,
                periodService.periodOf(periodDate), null);
        assertEquals(Money.zero(), r3.delta());
        assertNull(r3.adjustmentId());
        assertEquals(0, adjustmentRepository.findBySubscriptionAndPeriod(subId,
                periodService.periodOf(periodDate)).size(), "零净增绝不落单");
    }

    @Test
    @DisplayName("未收款补差已存在：相同输入不带 batchId 重放、再换新 batchId 重提，都回读同一笔")
    void existingUncollectedAdjustmentIsReadBackNotZero() {
        ingestionService.ingest("u-base", subId, "api_calls", 600, "2026-09-04T08:00:00Z");
        invoiceService.issue(subId, periodService.periodOf(periodDate));
        ingestionService.ingest("u-late", subId, "api_calls", 900, "2026-09-07T08:00:00Z");

        // 首次用显式批次键生成，补差挂在 OPEN 未收款。
        AdjustmentResult created = adjustmentService.settle(subId,
                periodService.periodOf(periodDate), "first-batch");
        assertTrue(created.created());
        assertEquals(Money.of("10.00"), created.delta());

        // 客户端超时后不带批次键重试：必须回读那笔未收款补差，不能返回 0/空单号。
        AdjustmentResult retryNoBatch = adjustmentService.settle(subId,
                periodService.periodOf(periodDate), null);
        assertFalse(retryNoBatch.created());
        assertEquals(created.adjustmentId(), retryNoBatch.adjustmentId());
        assertEquals(Money.of("10.00"), retryNoBatch.delta());

        // 同一批输入被处理了两遍，第二遍还换了个新 batchId：仍回读同一笔，不落第二笔。
        AdjustmentResult retryNewBatch = adjustmentService.settle(subId,
                periodService.periodOf(periodDate), "second-batch");
        assertFalse(retryNewBatch.created());
        assertEquals(created.adjustmentId(), retryNewBatch.adjustmentId());
        assertEquals(Money.of("10.00"), retryNewBatch.delta());

        assertEquals(1, adjustmentRepository.findBySubscriptionAndPeriod(subId,
                periodService.periodOf(periodDate)).size());
    }

    @Test
    @DisplayName("不带 batchId：多批新用量补差逐笔累加、交错重放一致，望远镜守恒且一笔只收一次")
    void multipleNullBatchesTelescopeAndConserve() {
        var period = periodService.periodOf(periodDate);
        ingestionService.ingest("t-base", subId, "api_calls", 1500, "2026-09-02T08:00:00Z");
        Invoice invoice = invoiceService.getInvoice(
                invoiceService.issue(subId, period).invoiceId());
        Money originalNet = invoice.total();
        assertTrue(paymentService.pay(invoice.id()).success());

        // 第一批迟到用量 -> 补差 80.00；立即重放（仍无新用量）必须回读同一笔。
        ingestionService.ingest("t-late-1", subId, "api_calls", 4000, "2026-09-06T08:00:00Z");
        AdjustmentResult a1 = adjustmentService.settle(subId, period, null);
        assertEquals(Money.of("80.00"), a1.delta());
        AdjustmentResult a1Replay = adjustmentService.settle(subId, period, null);
        assertEquals(a1.adjustmentId(), a1Replay.adjustmentId());
        assertEquals(Money.of("80.00"), a1Replay.delta());
        assertTrue(adjustmentService.pay(a1.adjustmentId()).success());
        // 收款后再重放：单号/金额依旧可复现，但不产生第二笔扣款。
        AdjustmentResult a1ReplayAfterPay = adjustmentService.settle(subId, period, null);
        assertEquals(a1.adjustmentId(), a1ReplayAfterPay.adjustmentId());
        assertEquals(Money.of("80.00"), a1ReplayAfterPay.delta());

        // 第二批新用量 -> 指纹变化 -> 新补差 10.00。
        ingestionService.ingest("t-late-2", subId, "api_calls", 500, "2026-09-07T08:00:00Z");
        AdjustmentResult a2 = adjustmentService.settle(subId, period, null);
        assertTrue(a2.created());
        assertEquals(Money.of("10.00"), a2.delta());
        assertTrue(adjustmentService.pay(a2.adjustmentId()).success());

        // 无新用量再触发（无 batchId）：仍回读最近这批输入对应的 a2，金额/单号一致，
        // 绝不落第三笔、绝不重复收款；望远镜口径下这一批的“新增净增”本就是 0，
        // 但已产生的补差不能被藏掉。
        AdjustmentResult a3 = adjustmentService.settle(subId, period, null);
        assertEquals(a2.adjustmentId(), a3.adjustmentId());
        assertEquals(Money.of("10.00"), a3.delta());
        assertFalse(a3.created());

        Money telescoped = originalNet.plus(a1.delta()).plus(a2.delta());
        assertEquals(a2.recalculatedTotal(), telescoped,
                "原账单净额 + 全部补差 = 最终全量重算应计总额");
        assertEquals(Money.of("200.00"), ledgerService.sum(subId, LedgerEntryType.CHARGE),
                "CHARGE=100(原)+80+10，重放不重复收款");
        assertEquals(2, adjustmentRepository.findBySubscriptionAndPeriod(subId, period).size());
    }

    @Test
    @DisplayName("混用 batchId 与不带 batchId 的并发/重试：同一批输入只落一笔，全部同单号同金额")
    void mixedBatchAndNoBatchConcurrentCallsCollapseToOne() throws Exception {
        ingestionService.ingest("mx-base", subId, "api_calls", 600, "2026-09-04T08:00:00Z");
        invoiceService.issue(subId, periodService.periodOf(periodDate));
        ingestionService.ingest("mx-late", subId, "api_calls", 900, "2026-09-07T08:00:00Z");

        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<AdjustmentResult> results = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < threads; i++) {
            final String batch = (i % 3 == 0) ? null : ("mx-batch-" + i);
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                results.add(adjustmentService.settle(subId,
                        periodService.periodOf(periodDate), batch));
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
        assertEquals(1, ids.size(), "同一批输入无论带不带 batchId，只能有一笔补差");
        assertEquals(Set.of("10.00"), deltas, "没有任何请求能拿到 0");
        assertEquals(1, results.stream().filter(AdjustmentResult::created).count());
        assertEquals(1, adjustmentRepository.findBySubscriptionAndPeriod(subId,
                periodService.periodOf(periodDate)).size());
    }

    @Test
    @DisplayName("补差收款失败挂起后，相同输入重放仍回读该笔（金额/单号一致），可继续重试收款")
    void failedUncollectedAdjustmentStillReadBack() {
        var period = periodService.periodOf(periodDate);
        ingestionService.ingest("d-base", subId, "api_calls", 600, "2026-09-04T08:00:00Z");
        invoiceService.issue(subId, period);
        ingestionService.ingest("d-late", subId, "api_calls", 900, "2026-09-07T08:00:00Z");

        AdjustmentResult a = adjustmentService.settle(subId, period, null);
        assertEquals(Money.of("10.00"), a.delta());
        gateway.setScript(subId, List.of(PaymentFailureReason.INSUFFICIENT_FUNDS));
        assertEquals(AdjustmentStatus.PAYMENT_FAILED,
                adjustmentService.pay(a.adjustmentId()).status());

        // 客户端重试结算（不带 batchId）：不能因为 delta 望远镜相减为 0 就藏掉这笔挂账。
        AdjustmentResult replay = adjustmentService.settle(subId, period, null);
        assertFalse(replay.created());
        assertEquals(a.adjustmentId(), replay.adjustmentId());
        assertEquals(Money.of("10.00"), replay.delta());
        assertEquals("PAYMENT_FAILED", replay.status());

        // 仍可对这同一笔继续重试收款，且只此一笔。
        var paid = adjustmentService.retry(a.adjustmentId());
        assertTrue(paid.success());
        assertEquals(AdjustmentStatus.PAID, paid.status());
        assertEquals(Money.of("10.00"), ledgerService.sum(subId, LedgerEntryType.CHARGE));
        assertEquals(1, adjustmentRepository.findBySubscriptionAndPeriod(subId, period).size());
    }
}
