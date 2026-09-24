package com.github.xingjianluo.usagebillingenginejava.service;

import com.github.xingjianluo.usagebillingenginejava.domain.BillingPeriod;
import com.github.xingjianluo.usagebillingenginejava.domain.Invoice;
import com.github.xingjianluo.usagebillingenginejava.domain.InvoiceLineItem;
import com.github.xingjianluo.usagebillingenginejava.domain.InvoiceStatus;
import com.github.xingjianluo.usagebillingenginejava.domain.Money;
import com.github.xingjianluo.usagebillingenginejava.domain.Plan;
import com.github.xingjianluo.usagebillingenginejava.domain.Subscription;
import com.github.xingjianluo.usagebillingenginejava.domain.SubscriptionVersion;
import com.github.xingjianluo.usagebillingenginejava.domain.UsageEvent;
import com.github.xingjianluo.usagebillingenginejava.repo.InvoiceRepository;
import com.github.xingjianluo.usagebillingenginejava.repo.PlanRepository;
import com.github.xingjianluo.usagebillingenginejava.repo.RepositoryLocks;
import com.github.xingjianluo.usagebillingenginejava.repo.SubscriptionRepository;
import com.github.xingjianluo.usagebillingenginejava.repo.UsageEventRepository;
import com.github.xingjianluo.usagebillingenginejava.service.dto.IssueInvoiceResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 发票服务。
 *
 * 开票模型（后付费，按自然月）：
 * 1. 基础费按周期内套餐版本切成若干时间片，用“全额 + 抵扣 + 新套餐按比例”三行表达一次变更：
 *      - BASE_FULL    : 周期起始套餐整月费用（恒 1 行）；
 *      - BASE_CREDIT  : 变更点之后未使用时间片对应的抵扣（每个变更点 1 行，负）；
 *      - BASE_PRORATED: 各新套餐在其生效片内的按比例费用（每个变更点 1 行，正）。
 *    净额恒等于纯按比例分摊 sum(月租 * 片占比)，升级补差价、降级退差价都精确守恒。
 * 2. 用量费按事件发生时刻所属版本的套餐阶梯费率分段计价（USAGE_TIER，逐事件一行）。
 *
 * 幂等：(subscriptionId, period) 唯一约束 + 订阅级锁，并发或重试调用只产生一张发票，
 * 后续调用返回 created=false 的既有发票。金额由确定性输入计算，重放金额可复现。
 */
@Service
public class InvoiceService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceService.class);

    private final InvoiceRepository invoiceRepository;
    private final UsageEventRepository usageEventRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PlanRepository planRepository;
    private final PeriodService periodService;
    private final PricingService pricingService;
    private final RepositoryLocks locks;
    private final Clock clock;

    /** 周期内一个套餐版本覆盖的时间片 [from, to)。 */
    private record Segment(String planCode, Instant from, Instant to) {
    }

    public InvoiceService(InvoiceRepository invoiceRepository,
                          UsageEventRepository usageEventRepository,
                          SubscriptionRepository subscriptionRepository,
                          PlanRepository planRepository,
                          PeriodService periodService,
                          PricingService pricingService,
                          RepositoryLocks locks, Clock clock) {
        this.invoiceRepository = invoiceRepository;
        this.usageEventRepository = usageEventRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.planRepository = planRepository;
        this.periodService = periodService;
        this.pricingService = pricingService;
        this.locks = locks;
        this.clock = clock;
    }

    public IssueInvoiceResult issue(String subscriptionId, BillingPeriod period) {
        if (period == null) {
            period = periodService.currentPeriod();
        }
        log.info("开票输入: subscriptionId={}, period=[{} {})", subscriptionId,
                period.start(), period.end());
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new IllegalArgumentException("订阅不存在: " + subscriptionId));
        final BillingPeriod p = period;

        ReentrantLock lock = locks.forKey("sub:" + subscriptionId);
        lock.lock();
        try {
            var existing = invoiceRepository.findBySubscriptionAndPeriod(subscriptionId, p);
            if (existing.isPresent()) {
                Invoice inv = existing.get();
                log.info("发票已存在，幂等返回: invoiceId={}, status={}, total={}（不重复开票）",
                        inv.id(), inv.status(), inv.total());
                return toResult(inv, false);
            }

            List<InvoiceLineItem> lines = buildLines(subscription, p);
            Invoice invoice = new Invoice(UUID.randomUUID().toString(), subscriptionId, p,
                    lines, Instant.now(clock));
            invoice.transitionTo(InvoiceStatus.OPEN, Instant.now(clock));

            Invoice concurrent = invoiceRepository.insertIfAbsent(invoice).orElse(null);
            if (concurrent != null) {
                log.info("并发开票落败，采用已插入发票: invoiceId={}, total={}",
                        concurrent.id(), concurrent.total());
                return toResult(concurrent, false);
            }
            log.info("发票已生成: invoiceId={}, 行数={}, 合计={}", invoice.id(),
                    lines.size(), invoice.total());
            lines.forEach(l -> log.info("  发票行: type={}, credit={}, amount={}, desc={}",
                    l.type(), l.credit(), l.amount(), l.description()));
            return toResult(invoice, true);
        } finally {
            lock.unlock();
        }
    }

    public Invoice getInvoice(String invoiceId) {
        return invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("发票不存在: " + invoiceId));
    }

    private List<InvoiceLineItem> buildLines(Subscription subscription, BillingPeriod period) {
        Instant periodStart = periodService.startInstant(period);
        Instant periodEnd = periodService.endInstant(period);
        List<Segment> segments = buildSegments(subscription.versions(), periodStart, periodEnd);

        List<InvoiceLineItem> lines = new ArrayList<>();

        // ---- 基础费（按天比例分摊，显式表达信用额度，每片只计一次）----
        // BASE_FULL（恒 1 行，正）：周期起始套餐整月费用。
        // 每个变更点 i 两行：
        //   BASE_CREDIT（负）：旧套餐在“新片 [seg.from, seg.to)”本应收的费用，
        //                      因套餐已变更而作为信用额度冲回；
        //   BASE_PRORATED（正）：新套餐在同一新片按天的实际费用。
        // 数学上：整月 - Σ旧套餐各变更片 + Σ新套餐各变更片 = Σ每片实际费用，
        // 每个时间片净计一次，升级表现为补差价、降级表现为信用退差，无重复扣费、无漏计。
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

        // ---- 用量费：按“事件时刻所属套餐片 + 计量项”聚合总量后套阶梯费率 ----
        // 阶梯费率对周期内同一计量项的累计用量生效（费率随总量跨档变化），
        // 而不是逐事件计价；事件已在仓储侧按 eventId 去重、按 occurredAt 排序。
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
        log.info("周期用量聚合: subscriptionId={}, meterTotals={}（已按 eventId 去重、occurredAt 排序）",
                subscription.id(), meterTotalsAll);

        // ---- 舍入守恒：逐行 HALF_UP 会产生分级舍入误差。
        // 基准 = 各时间片“月租 × 天数/月天数”用原始 BigDecimal 精确累加后一次舍入；
        // 行模型（全额 - 抵扣 + 新片费用）按行舍入后与该基准的差，以单行
        // ROUNDING_ADJUSTMENT 补齐（至多几分钱），保证发票合计等于精确分摊口径，
        // 结论确定、可复现、可解释。 ----
        BigDecimal exactBase = BigDecimal.ZERO;
        for (Segment seg : segments) {
            BigDecimal r = dayRatio(seg.from(), seg.to(), period);
            exactBase = exactBase.add(requirePlan(seg.planCode()).basePrice().value().multiply(r));
        }
        Money unroundedBase = new Money(exactBase);
        // 带符号汇总现有基础费行：抵扣行为负。
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
            log.info("舍入对齐: 纯比例基础费={}, 行合计={}, 调整={}{}",
                    unroundedBase, signedBase, credit ? "-" : "", abs);
        }
        return lines;
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

    private IssueInvoiceResult toResult(Invoice invoice, boolean created) {
        return new IssueInvoiceResult(invoice.id(), invoice.subscriptionId(),
                invoice.period().start().toString(), invoice.period().end().toString(),
                invoice.total(), invoice.status(), created);
    }
}
