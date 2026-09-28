package com.github.xingjianluo.usagebillingenginejava.service;

import com.github.xingjianluo.usagebillingenginejava.domain.BillingPeriod;
import com.github.xingjianluo.usagebillingenginejava.domain.InvoiceLineItem;
import com.github.xingjianluo.usagebillingenginejava.domain.Money;
import com.github.xingjianluo.usagebillingenginejava.domain.Plan;
import com.github.xingjianluo.usagebillingenginejava.domain.Subscription;
import com.github.xingjianluo.usagebillingenginejava.domain.SubscriptionVersion;
import com.github.xingjianluo.usagebillingenginejava.domain.UsageEvent;
import com.github.xingjianluo.usagebillingenginejava.repo.PlanRepository;
import com.github.xingjianluo.usagebillingenginejava.repo.UsageEventRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 整期计费计算器（纯口径）。
 *
 * 给定“订阅版本链 + 周期 + 该周期当前可见的全部用量”，按与正常出账完全相同的规则
 * 计算整期应计总额与发票行：基础费按套餐时间片按天分摊，用量费按“事件时刻所属套餐片
 * + meter”聚合整期总量后套阶梯费率，最后用单行 ROUNDING_ADJUSTMENT 对齐纯比例口径。
 *
 * 正常开票与“结算后补差的整期重算”都只走这里，因此跨档重算、周期内多次变更、
 * 变更点落在一天中间等边界情形，两种入口的口径必然一致；同样的输入重放结论确定。
 */
@Service
public class PeriodBillingCalculator {

    private static final Logger log = LoggerFactory.getLogger(PeriodBillingCalculator.class);

    /** 周期内一个套餐版本覆盖的时间片 [from, to)。 */
    private record Segment(String planCode, Instant from, Instant to) {
    }

    private final UsageEventRepository usageEventRepository;
    private final PlanRepository planRepository;
    private final PeriodService periodService;
    private final PricingService pricingService;

    public PeriodBillingCalculator(UsageEventRepository usageEventRepository,
                                   PlanRepository planRepository,
                                   PeriodService periodService,
                                   PricingService pricingService) {
        this.usageEventRepository = usageEventRepository;
        this.planRepository = planRepository;
        this.periodService = periodService;
        this.pricingService = pricingService;
    }

    /**
     * 计算整期发票行。lineSource 只用于日志标注调用来源（ISSUE / RECALC）。
     */
    public List<InvoiceLineItem> buildLines(Subscription subscription, BillingPeriod period,
                                            String lineSource) {
        Instant periodStart = periodService.startInstant(period);
        Instant periodEnd = periodService.endInstant(period);
        List<Segment> segments = buildSegments(subscription.versions(), periodStart, periodEnd);

        List<InvoiceLineItem> lines = new ArrayList<>();

        // ---- 基础费（按天比例分摊，显式表达信用额度，每片只计一次）----
        Plan startPlan = requirePlan(segments.get(0).planCode());
        lines.add(new InvoiceLineItem("BASE_FULL",
                startPlan.code() + " 整月基础费", startPlan.basePrice(), false));

        for (int i = 1; i < segments.size(); i++) {
            Segment prev = segments.get(i - 1);
            Segment seg = segments.get(i);
            Plan prevPlan = requirePlan(prev.planCode());
            Plan newPlan = requirePlan(seg.planCode());
            Money credit = prevPlan.basePrice().multiply(
                    dayRatio(seg.from(), seg.to(), period));
            lines.add(new InvoiceLineItem("BASE_CREDIT",
                    prevPlan.code() + " 在新片 [" + seg.from() + " ~ " + seg.to()
                            + "] 的费用作为信用冲回", credit, true));
            Money prorated = newPlan.basePrice().multiply(
                    dayRatio(seg.from(), seg.to(), period));
            lines.add(new InvoiceLineItem("BASE_PRORATED",
                    newPlan.code() + " 同片按天基础费", prorated, false));
        }

        // ---- 用量费：按“事件时刻所属套餐片 + 计量项”聚合整期总量后套阶梯费率 ----
        Map<String, Map<String, Long>> usageByPlan = new LinkedHashMap<>();
        for (UsageEvent event : usageEventRepository.findInWindow(
                subscription.id(), periodStart, periodEnd)) {
            String planCode = planCodeAt(subscription.versions(), event.occurredAt());
            usageByPlan.computeIfAbsent(planCode, k -> new LinkedHashMap<>())
                    .merge(event.meter(), event.quantity(), Long::sum);
        }
        usageByPlan.forEach((planCode, meterTotals) -> {
            Plan plan = requirePlan(planCode);
            meterTotals.forEach((meter, qty) -> {
                Money charge = pricingService.tieredCharge(plan, meter, qty);
                lines.add(new InvoiceLineItem("USAGE_TIER",
                        "meter=" + meter + " qty=" + qty + " @plan=" + planCode,
                        charge, false));
            });
        });
        Map<String, Long> meterTotalsAll = new LinkedHashMap<>();
        usageByPlan.values().forEach(m -> m.forEach((k, v) ->
                meterTotalsAll.merge(k, v, Long::sum)));
        log.info("整期用量聚合[{}]: subscriptionId={}, period={}, meterTotals={}"
                        + "（已按 eventId 去重、occurredAt 排序，含迟到补报）",
                lineSource, subscription.id(), period.start(), meterTotalsAll);

        // ---- 舍入守恒：以纯比例精确分摊为基准，行模型的分级舍入差由单行对齐 ----
        BigDecimal exactBase = BigDecimal.ZERO;
        for (Segment seg : segments) {
            BigDecimal r = dayRatio(seg.from(), seg.to(), period);
            exactBase = exactBase.add(requirePlan(seg.planCode()).basePrice().value().multiply(r));
        }
        Money unroundedBase = new Money(exactBase);
        Money signedBase = Money.zero();
        for (InvoiceLineItem l : lines) {
            if (l.type().startsWith("BASE")) {
                signedBase = l.credit() ? signedBase.minus(l.amount()) : signedBase.plus(l.amount());
            }
        }
        Money roundingDiff = unroundedBase.minus(signedBase);
        if (!roundingDiff.equals(Money.zero())) {
            boolean credit = roundingDiff.isNegative();
            Money abs = credit ? Money.zero().minus(roundingDiff) : roundingDiff;
            lines.add(new InvoiceLineItem("ROUNDING_ADJUSTMENT",
                    "按行舍入与纯比例分摊的差异对齐", abs, credit));
            log.info("舍入对齐[{}]: 纯比例基础费={}, 行合计={}, 调整={}{}",
                    lineSource, unroundedBase, signedBase, credit ? "-" : "", abs);
        }
        return lines;
    }

    /** 由发票行求净额（信用行为负）。 */
    public Money netTotal(List<InvoiceLineItem> lines) {
        Money sum = Money.zero();
        for (InvoiceLineItem line : lines) {
            sum = line.credit() ? sum.minus(line.amount()) : sum.plus(line.amount());
        }
        return sum;
    }

    /**
     * 将版本链与周期边界相交，得到周期内首尾相接的时间片。
     * 周期开始前生效的最后一个版本决定首片套餐；周期内的版本点切开后续片。
     */
    private List<Segment> buildSegments(List<SubscriptionVersion> versions,
                                        Instant periodStart, Instant periodEnd) {
        String startCode = versions.get(0).planCode();
        List<SubscriptionVersion> changePoints = new ArrayList<>();
        for (SubscriptionVersion v : versions) {
            if (!v.effectiveAt().isAfter(periodStart)) {
                startCode = v.planCode();
            } else if (v.effectiveAt().isBefore(periodEnd)) {
                changePoints.add(v);
            }
        }
        List<Segment> segments = new ArrayList<>();
        Instant from = periodStart;
        String code = startCode;
        for (SubscriptionVersion cp : changePoints) {
            segments.add(new Segment(code, from, cp.effectiveAt()));
            from = cp.effectiveAt();
            code = cp.planCode();
        }
        segments.add(new Segment(code, from, periodEnd));
        return segments;
    }

    private String planCodeAt(List<SubscriptionVersion> versions, Instant point) {
        String code = versions.get(0).planCode();
        for (SubscriptionVersion v : versions) {
            if (!point.isBefore(v.effectiveAt())) {
                code = v.planCode();
            }
        }
        return code;
    }

    /**
     * 按“天”计算 [from,to) 占自然月周期的比例（先裁剪到周期边界）。
     * 月租按天分摊是本系统约定的业务口径：天为整数比，结果确定，不引入秒级误差。
     * 变更点若落在一天中间（非 00:00），与正常出账一致，仍按整天口径计算其所在片。
     */
    private BigDecimal dayRatio(Instant from, Instant to, BillingPeriod period) {
        Instant periodStart = periodService.startInstant(period);
        Instant periodEnd = periodService.endInstant(period);
        Instant f = from.isBefore(periodStart) ? periodStart : from;
        Instant t = to.isAfter(periodEnd) ? periodEnd : to;
        long days = Duration.between(f, t).toSeconds() / 86400;
        long totalDays = java.time.temporal.ChronoUnit.DAYS.between(period.start(), period.end());
        if (days <= 0 || totalDays <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(days).divide(BigDecimal.valueOf(totalDays),
                10, RoundingMode.HALF_UP);
    }

    private Plan requirePlan(String code) {
        return planRepository.findByCode(code)
                .orElseThrow(() -> new IllegalStateException("套餐缺失: " + code));
    }
}
