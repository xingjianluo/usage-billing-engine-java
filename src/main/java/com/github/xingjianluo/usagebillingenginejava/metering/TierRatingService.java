package com.github.xingjianluo.usagebillingenginejava.metering;

import com.github.xingjianluo.usagebillingenginejava.model.MetricRate;
import com.github.xingjianluo.usagebillingenginejava.model.Money;
import com.github.xingjianluo.usagebillingenginejava.model.Tier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 阶梯费率计算。
 *
 * 规则（边际单价、确定性）：
 * - 总量 quantity 按顺序与每个 tier 的区间 [from, to) 求交；
 * - 落在区间内的数量 × 区间单价，逐段求和；
 * - 区间定义之外的数量按最后一个 open-ended 阶梯计价；若无任何阶梯则费用为 0。
 *
 * 该类不读取任何可变状态：相同 (quantity, rate) 永远得到相同金额，
 * 因而整月账单在重放/补算时严格可复现。
 */
@Service
public class TierRatingService {

    private static final Logger log = LoggerFactory.getLogger(TierRatingService.class);

    public Money rate(long quantity, MetricRate rate) {
        if (quantity <= 0 || rate == null || rate.tiers().isEmpty()) {
            return Money.ZERO;
        }
        BigDecimal total = BigDecimal.ZERO;
        long remaining = quantity;
        long cursor = 0L;
        for (Tier tier : rate.tiers()) {
            if (remaining <= 0) {
                break;
            }
            long inTier = quantityInTier(quantity, tier);
            if (inTier > 0) {
                BigDecimal part = tier.unitPrice().multiply(BigDecimal.valueOf(inTier));
                total = total.add(part);
                log.debug("阶梯计价 metric={} tier=[{},{}) inTier={} unitPrice={} part={}",
                        rate.metric(), tier.fromInclusive(),
                        tier.toExclusive() == null ? "∞" : tier.toExclusive(),
                        inTier, tier.unitPrice(), part);
            }
            cursor = tier.toExclusive() == null ? cursor : Math.max(cursor, tier.toExclusive());
            remaining = quantity - cursor;
        }
        Money result = new Money(total);
        log.info("阶梯计价结论 metric={} quantity={} amount={}", rate.metric(), quantity, result);
        return result;
    }

    /** 计算 quantity 与单个阶梯区间的交集数量。 */
    long quantityInTier(long quantity, Tier tier) {
        long from = Math.max(0L, tier.fromInclusive());
        if (quantity <= from) {
            return 0L;
        }
        if (tier.toExclusive() == null) {
            return quantity - from;
        }
        long to = tier.toExclusive();
        if (quantity <= to) {
            return quantity - from;
        }
        return Math.max(0L, to - from);
    }
}
