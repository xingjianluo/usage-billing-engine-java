package com.github.xingjianluo.usagebillingenginejava.billing;

import com.github.xingjianluo.usagebillingenginejava.metering.TierRatingService;
import com.github.xingjianluo.usagebillingenginejava.metering.UsageService;
import com.github.xingjianluo.usagebillingenginejava.model.Bill;
import com.github.xingjianluo.usagebillingenginejava.model.BillLine;
import com.github.xingjianluo.usagebillingenginejava.model.BillLineKind;
import com.github.xingjianluo.usagebillingenginejava.model.Money;
import com.github.xingjianluo.usagebillingenginejava.model.Plan;
import com.github.xingjianluo.usagebillingenginejava.model.PlanSegment;
import com.github.xingjianluo.usagebillingenginejava.model.Subscription;
import com.github.xingjianluo.usagebillingenginejava.pricing.PlanCatalog;
import com.github.xingjianluo.usagebillingenginejava.store.InMemoryStore;
import com.github.xingjianluo.usagebillingenginejava.subscription.SubscriptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 出账服务。
 *
 * 分摊模型（秒级精度，按比例 prorate）：
 * - 基础费：每个套餐片段承担 baseFee × segmentSeconds / periodSeconds。
 *   降级时高价旧套餐只承担片段内份额（等价于退回未使用部分的信用），
 *   升级时新套餐只承担生效后的份额；各片段份额之和即本期应付基础费。
 * - 用量费：按事件 occurredAt 归属到当时生效的套餐片段，使用该片段套餐的
 *   阶梯单价计价；因此跨变更点的同一计量项不会被双重计价或漏计。
 * - 尾差：HALF_UP 分摊产生的正负尾差集中并入最长片段，保证“明细之和 == 合计”。
 *
 * 幂等与可复现：
 * - 账单以 (accountId, periodStart) 为键，重复出账返回同一对象；
 * - 计算只依赖已落盘的订阅变更与去重事件，重放得到逐分相同的金额。
 */
@Service
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);

    private final InMemoryStore store;
    private final SubscriptionService subscriptionService;
    private final UsageService usageService;
    private final TierRatingService tierRatingService;
    private final PlanCatalog catalog;

    public BillingService(InMemoryStore store,
                          SubscriptionService subscriptionService,
                          UsageService usageService,
                          TierRatingService tierRatingService,
                          PlanCatalog catalog) {
        this.store = store;
        this.subscriptionService = subscriptionService;
        this.usageService = usageService;
        this.tierRatingService = tierRatingService;
        this.catalog = catalog;
    }

    public Bill billForPeriod(String accountId, Instant periodStart) {
        store.lockFor(accountId).lock();
        try {
            String key = store.billKey(accountId, periodStart);
            Bill existing = store.getBill(key);
            if (existing != null) {
                log.info("账单幂等命中 account={} period={} total={} -> 返回既有账单",
                        accountId, periodStart, existing.total());
                return existing;
            }

            Subscription subscription = subscriptionService.get(accountId);
            if (subscription == null) {
                throw new IllegalStateException("订阅不存在，无法出账: " + accountId);
            }
            Instant periodEnd = periodStart.plusSeconds(SubscriptionService.PERIOD_SECONDS);
            List<PlanSegment> segments =
                    subscriptionService.segments(subscription, periodStart, periodEnd);

            List<BillLine> lines = new ArrayList<>();
            addBaseFeeLines(segments, lines);
            addUsageLines(accountId, segments, lines);

            Money total = lines.stream().map(BillLine::amount).reduce(Money.ZERO, Money::add);
            Bill bill = new Bill(accountId, periodStart, periodEnd, List.copyOf(lines), total);
            store.putBill(key, bill);
            log.info("出账完成 account={} period=[{},{}) lines={} total={}",
                    accountId, periodStart, periodEnd, lines.size(), total);
            return bill;
        } finally {
            store.lockFor(accountId).unlock();
        }
    }

    /** 逐片段分摊基础费，并把分摊尾差并入最长片段，确保守恒。 */
    private void addBaseFeeLines(List<PlanSegment> segments, List<BillLine> lines) {
        Money proratedSum = Money.ZERO;
        int longestIndex = 0;
        long longestSeconds = -1;
        for (int i = 0; i < segments.size(); i++) {
            PlanSegment seg = segments.get(i);
            Plan plan = catalog.require(seg.planCode());
            Money share = plan.baseFee().prorate(seg.segmentSeconds(), seg.totalSeconds());
            lines.add(new BillLine(BillLineKind.PRORATED_BASE, seg.planCode(), null,
                    "基础费按秒分摊 [" + seg.from() + "," + seg.to() + ") "
                            + seg.segmentSeconds() + "/" + seg.totalSeconds() + "s",
                    seg.segmentSeconds(), share));
            proratedSum = proratedSum.add(share);
            if (seg.segmentSeconds() > longestSeconds) {
                longestSeconds = seg.segmentSeconds();
                longestIndex = i;
            }
        }
        // 尾差校正：同一套餐贯穿全周期时份额应严格等于其整周期基础费。
        PlanSegment longest = segments.get(longestIndex);
        Plan longestPlan = catalog.require(longest.planCode());
        Money expected = expectedBaseTotal(segments, longest.planCode());
        Money residual = expected.subtract(proratedSum);
        if (residual.amount().signum() != 0) {
            int lineIndex = baseLineIndexForPlan(lines, longest.planCode(), longestIndex);
            BillLine line = lines.get(lineIndex);
            BillLine adjusted = new BillLine(line.kind(), line.planCode(), line.metric(),
                    line.description() + "（含分摊尾差校正 " + residual + "）",
                    line.quantity(), line.amount().add(residual));
            lines.set(lineIndex, adjusted);
            log.info("分摊尾差校正 plan={} residual={} 并入最长片段", longest.planCode(), residual);
        }
    }

    /**
     * 期望基础费总额：若整周期只有一个套餐，等于该套餐整周期基础费；
     * 多片段（发生变更）时期望就是各份额之和（无单一基准），不做额外校正。
     */
    private Money expectedBaseTotal(List<PlanSegment> segments, String longestPlanCode) {
        boolean singlePlan = segments.stream().map(PlanSegment::planCode).distinct().count() == 1;
        if (!singlePlan) {
            return segments.stream()
                    .map(s -> catalog.require(s.planCode()).baseFee()
                            .prorate(s.segmentSeconds(), s.totalSeconds()))
                    .reduce(Money.ZERO, Money::add);
        }
        return catalog.require(longestPlanCode).baseFee();
    }

    private int baseLineIndexForPlan(List<BillLine> lines, String planCode, int segmentIndex) {
        int seen = -1;
        for (int i = 0; i < lines.size(); i++) {
            BillLine l = lines.get(i);
            if (l.kind() == BillLineKind.PRORATED_BASE && l.planCode().equals(planCode)) {
                seen++;
                if (seen == segmentIndex || i == segmentIndex) {
                    return i;
                }
            }
        }
        return segmentIndex;
    }

    /** 逐片段聚合用量并用该片段套餐的阶梯价计费。 */
    private void addUsageLines(String accountId, List<PlanSegment> segments, List<BillLine> lines) {
        for (PlanSegment seg : segments) {
            Plan plan = catalog.require(seg.planCode());
            Map<String, Long> usage = usageService.aggregateBetween(accountId, seg.from(), seg.to());
            // 排序保证明细顺序确定，账单可逐行复现
            List<String> metrics = new ArrayList<>(usage.keySet());
            metrics.sort(Comparator.naturalOrder());
            for (String metric : metrics) {
                long qty = usage.get(metric);
                Money amount = tierRatingService.rate(qty, plan.rateOf(metric));
                lines.add(new BillLine(BillLineKind.TIERED_USAGE, seg.planCode(), metric,
                        "阶梯用量 [" + seg.from() + "," + seg.to() + ") plan=" + seg.planCode(),
                        qty, amount));
            }
        }
    }
}
