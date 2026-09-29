package com.github.xingjianluo.usagebillingenginejava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.xingjianluo.usagebillingenginejava.domain.AdjustmentStatus;
import com.github.xingjianluo.usagebillingenginejava.domain.BillingPeriod;
import com.github.xingjianluo.usagebillingenginejava.domain.Invoice;
import com.github.xingjianluo.usagebillingenginejava.domain.LedgerEntryType;
import com.github.xingjianluo.usagebillingenginejava.domain.Money;
import com.github.xingjianluo.usagebillingenginejava.repo.AdjustmentRepository;
import com.github.xingjianluo.usagebillingenginejava.service.AdjustmentService;
import com.github.xingjianluo.usagebillingenginejava.service.InvoiceService;
import com.github.xingjianluo.usagebillingenginejava.service.LedgerService;
import com.github.xingjianluo.usagebillingenginejava.service.PaymentService;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 不提供可选批次标识（batchId=null）时的补差幂等回归：
 * 同一批输入的重放/客户端超时重试/并发触发必须返回完全一致的补差金额与补差单号，
 * 已产生但尚未收款的补差绝不能被“delta=0 + 空单号”藏掉；
 * 真正零净增才允许返回 0 且不落单；多批补差逐笔累加且望远镜守恒。
 */
@SpringBootTest
@Import(FixedClockConfig.class)
class AdjustmentNoBatchIdIdempotencyTest {

    private static final Logger log =
            LoggerFactory.getLogger(AdjustmentNoBatchIdIdempotencyTest.class);

    @Autowired private SubscriptionService subscriptionService;
    @Autowired private UsageIngestionService ingestionService;
    @Autowired private InvoiceService invoiceService;
    @Autowired private PaymentService paymentService;
    @Autowired private AdjustmentService adjustmentService;
    @Autowired private LedgerService ledgerService;
    @Autowired private PeriodService periodService;
    @Autowired private AdjustmentRepository adjustmentRepository;

    private String subId;
    private LocalDate periodDate;

    @BeforeEach
    void setUp() {
        TestResetter.resetAll();
        subId = "sub-adj-nobatch-" + System.nanoTime();
        subscriptionService.createSubscription(subId, "acct-" + subId, "BASIC",
                "2026-09-01T00:00:00Z");
        periodDate = LocalDate.of(2026, 9, 15);
    }

    private BillingPeriod period() {
        return periodService.periodOf(periodDate);
    }

    private Invoice issueSeptember() {
        return invoiceService.getInvoice(
                invoiceService.issue(subId, period()).invoiceId());
    }

    @Test
    @DisplayName("不带批次标识：同一批输入重放/超时重试返回同一单号、同一金额，未收款补差不被藏掉")
    void replayWithoutBatchIdReturnsSameUnpaidAdjustment() {
        ingestionService.ingest("nb-base", subId, "api_calls", 600, "2026-09-04T08:00:00Z");
        Invoice invoice = issueSeptember();
        ingestionService.ingest("nb-late", subId, "api_calls", 900, "2026-09-07T08:00:00Z");

        AdjustmentResult first = adjustmentService.settle(subId, period(), null);
        log.info("[关键输入] 不带 batchId 首次补差结算（600 出账后补报 900）");
        assertTrue(first.created(), "首次应创建补差单");
        assertNotNull(first.adjustmentId(), "必须返回补差单号");
        assertEquals(Money.of("10.00"), first.delta());
        assertEquals(AdjustmentStatus.OPEN.name(), first.status(),
                "补差已产生、尚未收款，挂在待收状态");

        // 客户端超时重试 / 同一批被处理两遍：均不带批次标识。
        AdjustmentResult retry = adjustmentService.settle(subId, period(), null);
        AdjustmentResult replay = adjustmentService.settle(subId, period(), null);
        log.info("[判定依据] 重放: id={}, delta={}, created={}；再重放: id={}, delta={}, created={}",
                retry.adjustmentId(), retry.delta(), retry.created(),
                replay.adjustmentId(), replay.delta(), replay.created());
        assertFalse(retry.created());
        assertFalse(replay.created());
        assertEquals(first.adjustmentId(), retry.adjustmentId(),
                "重放返回的补差单号必须与首次一致，不能是空");
        assertEquals(first.adjustmentId(), replay.adjustmentId());
        assertEquals(Money.of("10.00"), retry.delta(),
                "重放返回的补差金额必须与首次一致、可复现，绝不能返回 0");
        assertEquals(Money.of("10.00"), replay.delta());
        assertEquals(AdjustmentStatus.OPEN.name(), retry.status(), "仍然待收款");

        // 即便后续请求随意带了一个不同批次标识，输入相同也必须回读同一笔。
        AdjustmentResult withOtherBatch =
                adjustmentService.settle(subId, period(), "some-other-batch-id");
        assertEquals(first.adjustmentId(), withOtherBatch.adjustmentId());
        assertEquals(Money.of("10.00"), withOtherBatch.delta());

        assertEquals(1, adjustmentRepository.findBySubscriptionAndPeriod(subId, period()).size(),
                "同一批输入重放不得产生第二笔补差");
        assertEquals(Money.of("100.00"), invoiceService.getInvoice(invoice.id()).total(),
                "原发票金额不改写");

        // 收款只收一次：重放后再支付/再重放，不重复入账。
        assertTrue(adjustmentService.pay(first.adjustmentId()).success());
        adjustmentService.settle(subId, period(), null);
        adjustmentService.pay(first.adjustmentId());
        assertEquals(Money.of("10.00"), ledgerService.sum(subId, LedgerEntryType.CHARGE),
                "原发票 100 未支付，补差 10 只收一次，CHARGE 不得因重放/重复支付变成 20");
    }

    @Test
    @DisplayName("不带批次标识：并发触发同一批输入只创建一笔，所有请求拿到同一单号/金额，无人拿到 0")
    void concurrentSettleWithoutBatchIdProducesSingleAdjustment() throws Exception {
        ingestionService.ingest("nc-base", subId, "api_calls", 600, "2026-09-04T08:00:00Z");
        issueSeptember();
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
                results.add(adjustmentService.settle(subId, period(), null));
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
        log.info("[关键输入] {} 线程不带 batchId 并发结算同一批输入", threads);
        log.info("[判定依据] 结果数={}, 单号集合={}, created=true 次数={}, delta 集合={}",
                results.size(), ids, createdCount, deltas);
        assertEquals(1, results.size() == 0 ? 0 : ids.size(), "并发只能产生一笔补差");
        assertEquals(1, createdCount, "只有一个请求真正创建");
        assertEquals(Set.of("10.00"), deltas, "所有请求必须拿到 10.00，不能有请求拿到 0");
        assertFalse(ids.contains(null), "任何请求都不能拿到空补差单号");
        assertEquals(1, adjustmentRepository.findBySubscriptionAndPeriod(subId, period()).size());
    }

    @Test
    @DisplayName("真正零净增：无补报/补报仍在免费档时返回 0 且不落单；此前并无对应补差")
    void genuineZeroNetIncreaseReturnsZeroWithoutCreatingAdjustment() {
        ingestionService.ingest("nz-base", subId, "api_calls", 600, "2026-09-04T08:00:00Z");
        issueSeptember();

        AdjustmentResult none = adjustmentService.settle(subId, period(), null);
        log.info("[关键输入] 出账后无任何补报即触发补差");
        assertFalse(none.created());
        assertNull(none.adjustmentId(), "零净增不应返回补差单号");
        assertEquals(Money.zero(), none.delta());

        // 补报 100：整期累计 700，仍在 BASIC 免费档（1000 以内），整期重算无净增。
        ingestionService.ingest("nz-late-free", subId, "api_calls", 100,
                "2026-09-09T08:00:00Z");
        AdjustmentResult freeTier = adjustmentService.settle(subId, period(), null);
        log.info("[关键输入] 出账后补报 100（累计 700 仍在免费档）");
        log.info("[判定依据] delta={}, adjustmentId={}, created={}",
                freeTier.delta(), freeTier.adjustmentId(), freeTier.created());
        assertFalse(freeTier.created());
        assertNull(freeTier.adjustmentId());
        assertEquals(Money.zero(), freeTier.delta());
        assertEquals(Money.of("100.00"), freeTier.recalculatedTotal());
        assertEquals(Money.of("100.00"), freeTier.previouslyBilled());

        // 零净增重放仍是 0，不落单。
        AdjustmentResult zeroReplay = adjustmentService.settle(subId, period(), null);
        assertEquals(Money.zero(), zeroReplay.delta());
        assertNull(zeroReplay.adjustmentId());
        assertEquals(0, adjustmentRepository.findBySubscriptionAndPeriod(subId, period()).size());
        assertEquals(Money.zero(), ledgerService.sum(subId, LedgerEntryType.CHARGE),
                "原发票未支付且补差为零，账本无分录");
    }

    @Test
    @DisplayName("零净增之后再来真实净增仍正常成单，且同输入重放回读该笔而非 0")
    void zeroThenPositiveBatchThenReplay() {
        ingestionService.ingest("zp-base", subId, "api_calls", 600, "2026-09-04T08:00:00Z");
        issueSeptember();
        assertEquals(Money.zero(), adjustmentService.settle(subId, period(), null).delta());

        ingestionService.ingest("zp-late", subId, "api_calls", 900, "2026-09-07T08:00:00Z");
        AdjustmentResult positive = adjustmentService.settle(subId, period(), null);
        assertTrue(positive.created());
        assertEquals(Money.of("10.00"), positive.delta());
        assertNotNull(positive.adjustmentId());

        AdjustmentResult replay = adjustmentService.settle(subId, period(), null);
        assertEquals(positive.adjustmentId(), replay.adjustmentId());
        assertEquals(Money.of("10.00"), replay.delta());
        assertFalse(replay.created());
        assertEquals(1, adjustmentRepository.findBySubscriptionAndPeriod(subId, period()).size());
    }

    @Test
    @DisplayName("不带批次标识多批补差逐笔累加：原净额+Σ补差=最终全量重算，重放不落第二笔")
    void multipleBatchesWithoutBatchIdTelescopeAndConserve() {
        ingestionService.ingest("mt-base", subId, "api_calls", 1500, "2026-09-02T08:00:00Z");
        Invoice invoice = issueSeptember();
        Money originalNet = invoice.total();
        assertTrue(paymentService.pay(invoice.id()).success());

        ingestionService.ingest("mt-late-1", subId, "api_calls", 4000, "2026-09-06T08:00:00Z");
        AdjustmentResult a1 = adjustmentService.settle(subId, period(), null);
        assertEquals(Money.of("80.00"), a1.delta());
        // 第一批输入重放：回读 a1，不落新单。
        AdjustmentResult a1Replay = adjustmentService.settle(subId, period(), null);
        assertEquals(a1.adjustmentId(), a1Replay.adjustmentId());
        assertEquals(Money.of("80.00"), a1Replay.delta());
        assertTrue(adjustmentService.pay(a1.adjustmentId()).success());

        ingestionService.ingest("mt-late-2", subId, "api_calls", 500, "2026-09-07T08:00:00Z");
        AdjustmentResult a2 = adjustmentService.settle(subId, period(), null);
        assertEquals(Money.of("10.00"), a2.delta());
        assertEquals(Money.of("200.00"), a2.recalculatedTotal());
        assertEquals(Money.of("190.00"), a2.previouslyBilled());
        // 第二批输入重放：回读 a2（已收款也不能吞成 0），不重复收款。
        AdjustmentResult a2Replay = adjustmentService.settle(subId, period(), null);
        assertEquals(a2.adjustmentId(), a2Replay.adjustmentId());
        assertEquals(Money.of("10.00"), a2Replay.delta());
        assertTrue(adjustmentService.pay(a2.adjustmentId()).success());

        Money telescoped = originalNet.plus(a1.delta()).plus(a2.delta());
        log.info("[关键输入] 原 {} + 补差 {} + 补差 {} = {}，最终重算 {}",
                originalNet, a1.delta(), a2.delta(), telescoped, a2.recalculatedTotal());
        assertEquals(a2.recalculatedTotal(), telescoped,
                "原账单净额 + 该账期全部补差之和必须等于最终全量重算应计总额");
        assertEquals(Money.of("200.00"),
                ledgerService.sum(subId, LedgerEntryType.CHARGE),
                "重放与重复支付都不得重复入账");
        assertEquals(2, adjustmentRepository.findBySubscriptionAndPeriod(subId, period()).size(),
                "两批新用量只产生两笔补差");
    }
}
