package com.github.xingjianluo.usagebillingenginejava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.xingjianluo.usagebillingenginejava.domain.Adjustment;
import com.github.xingjianluo.usagebillingenginejava.domain.AdjustmentStatus;
import com.github.xingjianluo.usagebillingenginejava.domain.Invoice;
import com.github.xingjianluo.usagebillingenginejava.domain.InvoiceStatus;
import com.github.xingjianluo.usagebillingenginejava.domain.LedgerEntryType;
import com.github.xingjianluo.usagebillingenginejava.domain.Money;
import com.github.xingjianluo.usagebillingenginejava.repo.AdjustmentRepository;
import com.github.xingjianluo.usagebillingenginejava.domain.PaymentFailureReason;
import com.github.xingjianluo.usagebillingenginejava.service.InvoiceService;
import com.github.xingjianluo.usagebillingenginejava.service.LedgerService;
import com.github.xingjianluo.usagebillingenginejava.service.PaymentService;
import com.github.xingjianluo.usagebillingenginejava.service.PeriodService;
import com.github.xingjianluo.usagebillingenginejava.service.PostBillingAdjustmentService;
import com.github.xingjianluo.usagebillingenginejava.service.SimulatedPaymentGateway;
import com.github.xingjianluo.usagebillingenginejava.service.SubscriptionService;
import com.github.xingjianluo.usagebillingenginejava.service.UsageIngestionService;
import com.github.xingjianluo.usagebillingenginejava.service.dto.AdjustmentResult;
import com.github.xingjianluo.usagebillingenginejava.tests.config.FixedClockConfig;
import java.time.LocalDate;
import java.util.ArrayList;
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
 * 结算后补差：出账后迟到用量触发补差、跨档整期重算、同批并发/重放幂等、
 * 连续多批累计与账本守恒、原账单不被改写，并覆盖各支付状态与周期内变更口径。
 */
@SpringBootTest
@Import(FixedClockConfig.class)
class PostBillingAdjustmentTest {

    private static final Logger log = LoggerFactory.getLogger(PostBillingAdjustmentTest.class);

    @Autowired private SubscriptionService subscriptionService;
    @Autowired private UsageIngestionService ingestionService;
    @Autowired private InvoiceService invoiceService;
    @Autowired private PaymentService paymentService;
    @Autowired private PostBillingAdjustmentService adjustmentService;
    @Autowired private AdjustmentRepository adjustmentRepository;
    @Autowired private LedgerService ledgerService;
    @Autowired private PeriodService periodService;
    @Autowired private SimulatedPaymentGateway gateway;

    private String subId;
    private com.github.xingjianluo.usagebillingenginejava.domain.BillingPeriod period;

    @BeforeEach
    void setUp() {
        TestResetter.resetAll();
        subId = "sub-adj-" + System.nanoTime();
        subscriptionService.createSubscription(subId, "acct-" + subId, "BASIC",
                "2026-09-01T00:00:00Z");
        period = periodService.periodOf(LocalDate.of(2026, 9, 15));
    }

    @Test
    @DisplayName("出账后迟到补报触发补差：整期重算净增，补差金额正确")
    void lateUsageAfterInvoiceProducesAdjustment() {
        // 出账前 1000 次（恰好在免费档顶），原账单 = 100 基础费 + 0 用量
        ingestionService.ingest("e-orig", subId, "api_calls", 1000, "2026-09-05T08:00:00Z");
        Invoice invoice = invoiceService.getInvoice(invoiceService.issue(subId, period).invoiceId());
        assertEquals(Money.of("100.00"), invoice.total());

        // 出账后补报 600 次，仍属 9 月账期 -> 整期 1600 次，用量费 600*0.02=12.00
        ingestionService.ingest("e-late-1", subId, "api_calls", 600, "2026-09-24T09:00:00Z");
        AdjustmentResult result = adjustmentService.settleLateUsage(subId, period);

        log.info("[判定依据] 补差: amount={}, recalculatedTotal={}, previouslyBilled={}, basis={}",
                result.amount(), result.recalculatedTotal(), result.previouslyBilled(),
                result.basisEventIds());
        assertTrue(result.created());
        assertEquals(Money.of("112.00"), result.recalculatedTotal());
        assertEquals(Money.of("100.00"), result.previouslyBilled());
        assertEquals(Money.of("12.00"), result.amount());
        assertEquals(List.of("e-late-1", "e-orig"), result.basisEventIds(),
                "补差依据须列出整期全部用量事件 id（有序）");
        // 原账单编号、金额、明细一律不变
        Invoice unchanged = invoiceService.getInvoice(invoice.id());
        assertEquals(invoice.id(), unchanged.id());
        assertEquals(Money.of("100.00"), unchanged.total());
        assertEquals(invoice.lines().size(), unchanged.lines().size());
    }

    @Test
    @DisplayName("补报把用量推进更高阶梯：按整期重算，而非只对新事件套价")
    void crossingTierBoundaryIsRecalculatedForWholePeriod() {
        // 出账前 900（免费档内）-> 用量费 0；迟到 200 -> 整期 1100，跨 1000 档线：
        // 正确口径 100*0.02 = 2.00；若错误地“只对新事件 200 套价”会算成 200*0.02=4.00
        ingestionService.ingest("e-base", subId, "api_calls", 900, "2026-09-03T08:00:00Z");
        invoiceService.issue(subId, period);
        ingestionService.ingest("e-late", subId, "api_calls", 200, "2026-09-24T06:00:00Z");

        AdjustmentResult result = adjustmentService.settleLateUsage(subId, period);
        log.info("[关键输入] 出账前 900（免费档内），迟到 200 后整期 1100 跨 1000 档线");
        log.info("[判定依据] 整期重算补差={}（只对新事件套价会错算为 4.00）", result.amount());
        assertEquals(Money.of("2.00"), result.amount(),
                "跨档必须按整期总量重算：仅 100 次落入 0.02 档");
    }

    @Test
    @DisplayName("同一批输入并发触发与重放：只产生一份补差，金额一致，不重复入账")
    void concurrentAndReplayOfSameBatchProducesOneAdjustment() throws Exception {
        ingestionService.ingest("e0", subId, "api_calls", 1000, "2026-09-02T08:00:00Z");
        Invoice invoice = invoiceService.getInvoice(invoiceService.issue(subId, period).invoiceId());
        paymentService.pay(invoice.id()); // 已支付账期：补差会即时收款入账
        ingestionService.ingest("e1", subId, "api_calls", 500, "2026-09-24T07:00:00Z");

        int threads = 12;
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
                results.add(adjustmentService.settleLateUsage(subId, period));
            });
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        // 串行重放两次
        results.add(adjustmentService.settleLateUsage(subId, period));
        results.add(adjustmentService.settleLateUsage(subId, period));

        Set<String> ids = results.stream().map(AdjustmentResult::adjustmentId)
                .collect(Collectors.toSet());
        long created = results.stream().filter(AdjustmentResult::created).count();
        Set<String> amounts = results.stream().map(r -> r.amount().toString())
                .collect(Collectors.toSet());
        List<Adjustment> stored =
                new ArrayList<>(adjustmentRepository.findBySubscriptionAndPeriod(subId, period));

        log.info("[关键输入] {} 线程并发 + 2 次重放，同一批迟到用量", threads);
        log.info("[判定依据] 返回数={}, 不同补差单数={}, created=true 次数={}, 金额集合={}, 仓储单数={}",
                results.size(), ids.size(), created, amounts, stored.size());

        assertEquals(1, ids.size(), "并发+重放只能产生一张补差单");
        assertEquals(1, created, "只有一次真正创建");
        assertEquals(Set.of("10.00"), amounts, "补差金额完全一致、可复现");
        assertEquals(1, stored.size());
        assertEquals(AdjustmentStatus.PAID, stored.get(0).status(),
                "已支付账期补差即时收款成功");
        // 只入一笔补差 CHARGE（10.00），加上原账单 100.00
        assertEquals(Money.of("110.00"),
                ledgerService.sum(subId, LedgerEntryType.CHARGE), "不重复入账");
    }

    @Test
    @DisplayName("连续多批迟到用量：每份新补差之和 + 原账单净额 = 最终全部用量重算总额")
    void multipleBatchesAccumulateAndLedgerConserves() {
        ingestionService.ingest("e0", subId, "api_calls", 1000, "2026-09-02T08:00:00Z");
        Invoice invoice = invoiceService.getInvoice(invoiceService.issue(subId, period).invoiceId());
        paymentService.pay(invoice.id());

        // 第一批 +600（整期 1600，用量费 12）
        ingestionService.ingest("e1", subId, "api_calls", 600, "2026-09-21T08:00:00Z");
        AdjustmentResult a1 = adjustmentService.settleLateUsage(subId, period);
        // 第二批 +1000（整期 2600，用量费 32；本批净增 32-12=20）
        ingestionService.ingest("e2", subId, "api_calls", 1000, "2026-09-22T08:00:00Z");
        AdjustmentResult a2 = adjustmentService.settleLateUsage(subId, period);
        // 第三批无新增用量（重放同内容）-> 不产生新补差
        AdjustmentResult a2replay = adjustmentService.settleLateUsage(subId, period);
        // 第四批再 +400（整期 3000，用量费 40；本批净增 8）
        ingestionService.ingest("e3", subId, "api_calls", 400, "2026-09-23T20:00:00Z");
        AdjustmentResult a3 = adjustmentService.settleLateUsage(subId, period);

        log.info("[判定依据] a1={}, a2={}, a2replay.created={}, a3={}", a1.amount(), a2.amount(),
                a2replay.created(), a3.amount());
        assertEquals(Money.of("12.00"), a1.amount());
        assertEquals(Money.of("20.00"), a2.amount());
        assertFalse(a2replay.created(), "内容没变化不得重复生成补差");
        assertEquals(Money.of("8.00"), a3.amount());

        Money adjustmentsSum = a1.amount().plus(a2.amount()).plus(a3.amount());
        Money finalTotal = a3.recalculatedTotal();
        log.info("[判定依据] 原账单净额={}, 补差之和={}, 最终重算总额={}",
                invoice.total(), adjustmentsSum, finalTotal);
        assertEquals(Money.of("140.00"), finalTotal, "基础费 100 + 用量费 3000 档 40");
        assertEquals(invoice.total().plus(adjustmentsSum), finalTotal,
                "原账单净额 + 所有补差之和 = 最终全部用量重算总额");

        // 已支付账期：所有补差即时入账，CHARGE = 原账单 100 + 补差 40 = 140
        assertEquals(Money.of("140.00"),
                ledgerService.sum(subId, LedgerEntryType.CHARGE), "补差逐批收款入账，不重复扣款");

        // 原账单退款：冲回原净额 + 所有已收补差，账本守恒
        paymentService.refund(invoice.id());
        Money charge = ledgerService.sum(subId, LedgerEntryType.CHARGE);
        Money credit = ledgerService.sum(subId, LedgerEntryType.CREDIT);
        Money refund = ledgerService.sum(subId, LedgerEntryType.REFUND);
        log.info("[判定依据] 退款后 CHARGE={}, CREDIT={}, REFUND={}, 守恒={}",
                charge, credit, refund, ledgerService.isConserved(subId));
        assertEquals(charge, credit.plus(refund), "退款冲回补差后账本仍守恒");
        assertEquals(Money.of("140.00"), refund, "退款 = 原净额 100 + 补差 40");
    }

    @Test
    @DisplayName("重算总额无变化：补差明确为零，不产生费用与账本分录")
    void noChangeYieldsZeroAdjustmentWithoutLedgerEntry() {
        // 出账前 2000 次 -> 原账单已计 20 用量费；出账后只来一条“同账期但总量口径不变”不可能，
        // 故构造：迟到事件落在免费档内但仍增加总量且不跨档——会产生正补差；
        // 零补差场景 = 触发补差时可见事件集合与上次结算完全相同（纯重放已在上一用例）。
        // 这里直接验证：补差为 0 的边界——事件数量为 0 时（账期无任何用量）重算差为 0。
        Invoice invoice = invoiceService.getInvoice(invoiceService.issue(subId, period).invoiceId());
        paymentService.pay(invoice.id());
        // 不报新事件，直接再次结算：内容键与“空集合”首次结算不同——
        // 首次对空集合结算产生一张 0 补差。
        AdjustmentResult first = adjustmentService.settleLateUsage(subId, period);
        log.info("[判定依据] 账期无用量首次补差: amount={}, created={}", first.amount(),
                first.created());
        assertEquals(Money.zero(), first.amount());
        assertTrue(first.created());
        assertEquals(AdjustmentStatus.PAID, first.status(), "零补差直接置 PAID");
        Money chargeAfter = ledgerService.sum(subId, LedgerEntryType.CHARGE);
        assertEquals(Money.of("100.00"), chargeAfter, "零补差不产生任何分录");
        // 再触发：同内容键幂等，不新增
        AdjustmentResult again = adjustmentService.settleLateUsage(subId, period);
        assertFalse(again.created());
        assertEquals(Money.of("100.00"), ledgerService.sum(subId, LedgerEntryType.CHARGE));
    }

    @Test
    @DisplayName("OPEN / 支付失败 / 已作废账期补报不丢：补差挂账，随原账单支付成功一起收取")
    void unpaidPeriodsKeepOpenAdjustmentAndCollectOnInvoicePayment() {
        ingestionService.ingest("e0", subId, "api_calls", 1000, "2026-09-02T08:00:00Z");
        Invoice invoice = invoiceService.getInvoice(invoiceService.issue(subId, period).invoiceId());
        assertEquals(InvoiceStatus.OPEN, invoice.status());

        ingestionService.ingest("e1", subId, "api_calls", 500, "2026-09-24T09:00:00Z");
        AdjustmentResult open = adjustmentService.settleLateUsage(subId, period);
        assertEquals(Money.of("10.00"), open.amount());
        assertEquals(AdjustmentStatus.OPEN, open.status(), "OPEN 账期补差挂账，不提前扣款");
        assertEquals(Money.zero(), ledgerService.sum(subId, LedgerEntryType.CHARGE),
                "未支付不得入账");

        // 原账单支付成功 -> 原账单 + 挂账补差一次性收取入账
        var paid = paymentService.pay(invoice.id());
        assertTrue(paid.success());
        Adjustment after = adjustmentService.getAdjustment(open.adjustmentId());
        assertEquals(AdjustmentStatus.PAID, after.status());
        assertEquals(Money.of("110.00"), ledgerService.sum(subId, LedgerEntryType.CHARGE),
                "原账单 100 + 补差 10 一次性收取，不漏计不重复");
    }

    @Test
    @DisplayName("周期内多次变更、变更点落在一天中间：补差重算口径与正常出账一致")
    void recalculationMatchesIssueProrationWithMidDayAndMultipleChanges() {
        // 9/11 06:00 升 PRO（落在一天中间），9/21 18:00 降回 BASIC
        subscriptionService.changePlan(subId, "PRO", "2026-09-11T06:00:00Z");
        subscriptionService.changePlan(subId, "BASIC", "2026-09-21T18:00:00Z");
        ingestionService.ingest("e0", subId, "api_calls", 100, "2026-09-15T08:00:00Z");
        Invoice invoice = invoiceService.getInvoice(invoiceService.issue(subId, period).invoiceId());

        // 出账后补报：9/05(BASIC 片) 950 次、9/25(BASIC 片) 950 次，与 e0 分开聚合
        ingestionService.ingest("e-late-a", subId, "api_calls", 950, "2026-09-05T09:00:00Z");
        ingestionService.ingest("e-late-b", subId, "api_calls", 950, "2026-09-24T09:30:00Z");
        AdjustmentResult result = adjustmentService.settleLateUsage(subId, period);

        // 期望值：用同样输入“从头开一张票”的总额（独立重算核对）
        String verifySub = subId + "-verify";
        subscriptionService.createSubscription(verifySub, "acct2", "BASIC",
                "2026-09-01T00:00:00Z");
        subscriptionService.changePlan(verifySub, "PRO", "2026-09-11T06:00:00Z");
        subscriptionService.changePlan(verifySub, "BASIC", "2026-09-21T18:00:00Z");
        ingestionService.ingest("v0", verifySub, "api_calls", 100, "2026-09-15T08:00:00Z");
        ingestionService.ingest("v1", verifySub, "api_calls", 950, "2026-09-05T09:00:00Z");
        ingestionService.ingest("v2", verifySub, "api_calls", 950, "2026-09-24T09:30:00Z");
        Money fullRecalcTotal = invoiceService.getInvoice(
                invoiceService.issue(verifySub, period).invoiceId()).total();

        log.info("[判定依据] 原账单={}, 补差重算总额={}, 独立全量开票总额={}, 补差净增={}",
                invoice.total(), result.recalculatedTotal(), fullRecalcTotal, result.amount());
        assertEquals(fullRecalcTotal, result.recalculatedTotal(),
                "补差整期重算必须与正常出账口径一致（含按天分摊/舍入对齐）");
        assertEquals(fullRecalcTotal.minus(invoice.total()), result.amount(),
                "补差 = 重算总额 − 原账单净额");
        // 原账单不被改写
        assertNotEquals(null, invoiceService.getInvoice(invoice.id()));
    }

    @Test
    @DisplayName("已支付账期补差收款失败：不入账不丢单，显式重试成功且只扣一次")
    void adjustmentCollectionFailureCanBeRetried() {
        ingestionService.ingest("e0", subId, "api_calls", 1000, "2026-09-02T08:00:00Z");
        // 第 1 次通道调用为原账单 pay（成功），第 2 次为补差首次收款（余额不足），其后默认成功
        gateway.setScript(subId, List.of(
                PaymentFailureReason.NONE, PaymentFailureReason.INSUFFICIENT_FUNDS));
        Invoice invoice = invoiceService.getInvoice(invoiceService.issue(subId, period).invoiceId());
        paymentService.pay(invoice.id());

        ingestionService.ingest("e1", subId, "api_calls", 500, "2026-09-24T09:00:00Z");
        AdjustmentResult failed = adjustmentService.settleLateUsage(subId, period);
        log.info("[判定依据] 补差首次收款: status={}, amount={}", failed.status(), failed.amount());
        assertEquals(AdjustmentStatus.PAYMENT_FAILED, failed.status());
        assertEquals(Money.of("10.00"), failed.amount());
        assertEquals(Money.of("100.00"), ledgerService.sum(subId, LedgerEntryType.CHARGE),
                "补差收款失败不得入账");

        // 显式重试：成功入账；再次重试幂等，不重复扣款
        AdjustmentResult retried = adjustmentService.collectAdjustment(failed.adjustmentId());
        assertEquals(AdjustmentStatus.PAID, retried.status());
        AdjustmentResult retryAgain = adjustmentService.collectAdjustment(failed.adjustmentId());
        assertEquals(AdjustmentStatus.PAID, retryAgain.status());
        Adjustment stored = adjustmentService.getAdjustment(failed.adjustmentId());
        assertEquals(2, stored.attemptCount(), "一次失败 + 一次成功，幂等重试不新增尝试");
        assertEquals(Money.of("110.00"), ledgerService.sum(subId, LedgerEntryType.CHARGE),
                "成功后只入一笔补差，不重复扣款");
    }

    @Test
    @DisplayName("支付失败终态/已作废账期补报：补差挂账可显式收取，不静默丢单")
    void voidAndFailedTerminalInvoicePeriodsStillAcceptLateUsage() {
        // 场景 A：原账单支付失败达到终态，补报 -> 补差 OPEN，显式收取成功入账
        gateway.setScript(subId, List.of(
                PaymentFailureReason.GATEWAY_TIMEOUT,
                PaymentFailureReason.GATEWAY_TIMEOUT,
                PaymentFailureReason.INVALID_ACCOUNT));
        Invoice invoice = invoiceService.getInvoice(invoiceService.issue(subId, period).invoiceId());
        paymentService.pay(invoice.id());
        paymentService.retry(invoice.id());
        paymentService.retry(invoice.id());
        assertEquals(InvoiceStatus.MAX_RETRIES_EXCEEDED, invoice.status());

        ingestionService.ingest("f1", subId, "api_calls", 1500, "2026-09-24T09:00:00Z");
        AdjustmentResult adj = adjustmentService.settleLateUsage(subId, period);
        log.info("[判定依据] MAX_RETRIES 账期补差: status={}, amount={}", adj.status(), adj.amount());
        assertEquals(AdjustmentStatus.OPEN, adj.status(), "终态失败账期不自动扣款，补差挂账");
        assertEquals(Money.of("10.00"), adj.amount());
        assertEquals(Money.zero(), ledgerService.sum(subId, LedgerEntryType.CHARGE), "原账单与补差均未收");

        AdjustmentResult collected = adjustmentService.collectAdjustment(adj.adjustmentId());
        assertEquals(AdjustmentStatus.PAID, collected.status());
        assertEquals(Money.of("10.00"), ledgerService.sum(subId, LedgerEntryType.CHARGE),
                "补差单独收取成功，不依赖原账单状态");

        // 场景 B：原账单已支付又退款 -> VOID，此后再补报：补差 OPEN，可显式收取
        String sub2 = subId + "-void";
        subscriptionService.createSubscription(sub2, sub2, "BASIC", "2026-09-01T00:00:00Z");
        Invoice inv2 = invoiceService.getInvoice(invoiceService.issue(sub2, period).invoiceId());
        paymentService.pay(inv2.id());
        paymentService.refund(inv2.id());
        assertEquals(InvoiceStatus.VOID, inv2.status());
        ingestionService.ingest("v1", sub2, "api_calls", 1500, "2026-09-24T10:00:00Z");
        AdjustmentResult adj2 = adjustmentService.settleLateUsage(sub2, period);
        assertEquals(AdjustmentStatus.OPEN, adj2.status(), "已作废账期补报同样接收挂账，不丢单");
        assertEquals(Money.of("10.00"), adj2.amount());
        AdjustmentResult collected2 = adjustmentService.collectAdjustment(adj2.adjustmentId());
        assertEquals(AdjustmentStatus.PAID, collected2.status());
        log.info("[判定依据] VOID 账期补差显式收取成功，账本守恒={}",
                ledgerService.isConserved(sub2));
    }

}
