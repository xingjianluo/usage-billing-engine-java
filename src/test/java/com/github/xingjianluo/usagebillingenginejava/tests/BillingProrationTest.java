package com.github.xingjianluo.usagebillingenginejava.tests;

import com.github.xingjianluo.usagebillingenginejava.billing.BillingService;
import com.github.xingjianluo.usagebillingenginejava.metering.TierRatingService;
import com.github.xingjianluo.usagebillingenginejava.metering.UsageService;
import com.github.xingjianluo.usagebillingenginejava.model.Bill;
import com.github.xingjianluo.usagebillingenginejava.model.BillLine;
import com.github.xingjianluo.usagebillingenginejava.model.BillLineKind;
import com.github.xingjianluo.usagebillingenginejava.model.Money;
import com.github.xingjianluo.usagebillingenginejava.model.UsageEvent;
import com.github.xingjianluo.usagebillingenginejava.pricing.PlanCatalog;
import com.github.xingjianluo.usagebillingenginejava.store.InMemoryStore;
import com.github.xingjianluo.usagebillingenginejava.subscription.SubscriptionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 周期内变更与分摊测试：
 * - 变更点两侧基础费按秒 50/50 分摊；
 * - 用量按发生时刻的套餐费率分段阶梯计费；
 * - 明细之和 == 合计（守恒）；重复出账/重放金额一致（可复现）。
 */
class BillingProrationTest {

    private static final Logger log = LoggerFactory.getLogger(BillingProrationTest.class);

    private final InMemoryStore store = new InMemoryStore();
    private final PlanCatalog catalog = new PlanCatalog();
    private final SubscriptionService subscriptionService = new SubscriptionService(store, catalog);
    private final UsageService usageService = new UsageService(store);
    private final TierRatingService tierRatingService = new TierRatingService();
    private final BillingService billingService = new BillingService(
            store, subscriptionService, usageService, tierRatingService, catalog);

    /** 锚点取 15 天前 + 60 秒，使变更点恰落在“现在前 60 秒”，事件均在可接受窗口内。 */
    private TestClock setup(String account, String fromPlan, String toPlan) {
        Instant anchor = Instant.now().truncatedTo(ChronoUnit.SECONDS)
                .minus(Duration.ofDays(15)).minusSeconds(60);
        Instant changeAt = anchor.plus(Duration.ofDays(15)); // 正好半周期，= now-60s
        subscriptionService.create(account, anchor, fromPlan);
        subscriptionService.changePlan(account, changeAt, toPlan);
        return new TestClock(anchor, changeAt);
    }

    private record TestClock(Instant anchor, Instant changeAt) {}

    private void report(String id, String account, String metric, long qty, Instant at) {
        usageService.ingest(new UsageEvent(id, account, metric, qty, at, Instant.now()));
    }

    private Money sumOf(Bill bill, BillLineKind kind) {
        return bill.lines().stream()
                .filter(l -> l.kind() == kind)
                .map(BillLine::amount)
                .reduce(Money.ZERO, Money::add);
    }

    @Test
    @DisplayName("半周期升级 FREE->PRO：基础费对半分摊，用量跨点分段计价，重放金额可复现")
    void upgradeMidCycle() {
        TestClock c = setup("acct-up", PlanCatalog.FREE, PlanCatalog.PRO);
        // 变更前 500 次（FREE 前 1000 免费 -> 0）；变更后 12000 次（PRO 阶梯 -> 130.00）
        report("up-e1", "acct-up", "api_calls", 500, c.changeAt().minusSeconds(30));
        report("up-e2", "acct-up", "api_calls", 12_000, c.changeAt().plusSeconds(10));

        Bill bill = billingService.billForPeriod("acct-up", c.anchor());
        log.info("[关键输入] FREE->PRO@半周期, 变更前500次 变更后12000次 [判定依据] 基础费 99*0.5=49.50; "
                + "用量 0 + (10000*0.01+2000*0.015)=130.00; 账单明细={}", bill.lines());

        assertEquals(new Money(new java.math.BigDecimal("49.50")),
                sumOf(bill, BillLineKind.PRORATED_BASE), "基础费对半分摊");
        assertEquals(Money.of(130), sumOf(bill, BillLineKind.TIERED_USAGE), "用量仅按变更后 PRO 费率计大额");
        assertEquals(new Money(new java.math.BigDecimal("179.50")), bill.total());

        // 守恒：逐行之和 == 合计
        Money lineSum = bill.lines().stream().map(BillLine::amount).reduce(Money.ZERO, Money::add);
        assertEquals(bill.total(), lineSum, "明细之和必须等于合计");

        // 幂等：同一周期重复出账返回同一账单
        Bill again = billingService.billForPeriod("acct-up", c.anchor());
        assertEquals(bill, again);

        // 可复现：把事件全部重放一遍（重复上报），重新计算金额不变
        report("up-e1", "acct-up", "api_calls", 500, c.changeAt().minusSeconds(30));
        report("up-e2", "acct-up", "api_calls", 12_000, c.changeAt().plusSeconds(10));
        assertEquals(new Money(new java.math.BigDecimal("179.50")), bill.total(),
                "重复上报重放不得改变账单金额");
        log.info("[结论] 重复出账与事件重放后 total 仍为 179.50");
    }

    @Test
    @DisplayName("半周期降级 ENTERPRISE->PRO：旧高价套餐只承担半周期份额（信用守恒，不重复扣费）")
    void downgradeMidCycle() {
        TestClock c = setup("acct-down", PlanCatalog.ENTERPRISE, PlanCatalog.PRO);
        report("down-e1", "acct-down", "api_calls", 10_000, c.changeAt().minusSeconds(20));
        report("down-e2", "acct-down", "api_calls", 10_000, c.changeAt().plusSeconds(20));

        Bill bill = billingService.billForPeriod("acct-down", c.anchor());
        // 基础费：299*0.5 + 99*0.5 = 199.00
        // 用量：ENTERPRISE 段 10000*0.008=80；PRO 段 10000*0.01=100 -> 180
        log.info("[关键输入] ENTERPRISE->PRO@半周期 两侧各10000次 [判定依据] 基础费 299/2+99/2=199; "
                + "用量 10000*0.008=80 + 10000*0.01=100 = 180; 合计={}", bill.total());

        assertEquals(Money.of(199), sumOf(bill, BillLineKind.PRORATED_BASE),
                "降级后高价旧套餐只收半周期（等价信用返还未用部分）");
        assertEquals(Money.of(180), sumOf(bill, BillLineKind.TIERED_USAGE));
        assertEquals(Money.of(379), bill.total());

        Money lineSum = bill.lines().stream().map(BillLine::amount).reduce(Money.ZERO, Money::add);
        assertEquals(bill.total(), lineSum);
        assertTrue(bill.lines().stream().noneMatch(l -> l.amount().isNegative()),
                "分段分摊模型下无需负信用行，也不应出现负数行");
    }

    @Test
    @DisplayName("无变更贯穿全周期：基础费等于整周期价，不被错误按比例削减")
    void noChangeFullPeriod() {
        Instant anchor = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(3_600);
        subscriptionService.create("acct-flat", anchor, PlanCatalog.PRO);
        report("flat-e1", "acct-flat", "api_calls", 5_000, anchor.plusSeconds(60));

        Bill bill = billingService.billForPeriod("acct-flat", anchor);
        log.info("[关键输入] PRO 贯穿周期, 5000次 [判定依据] 单一片段基础费应为 99.00（尾差校正后），"
                + "用量 5000*0.01=50.00，合计={}", bill.total());
        assertEquals(Money.of(99), sumOf(bill, BillLineKind.PRORATED_BASE),
                "单一片段即使秒数非整也必须通过尾差校正等于整价");
        assertEquals(Money.of(149), bill.total());
    }
}
