package com.github.xingjianluo.usagebillingenginejava.service;

import com.github.xingjianluo.usagebillingenginejava.domain.MeterTier;
import com.github.xingjianluo.usagebillingenginejava.domain.Money;
import com.github.xingjianluo.usagebillingenginejava.domain.Plan;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 定价服务。
 * 阶梯费率采用“分段计价”：用量落在每一档 (from, to] 的部分分别按该档单价计费后求和，
 * 而非整单套用一个档位价。金额一律 HALF_UP 保留两位，纯函数，重放结果确定。
 */
@Service
public class PricingService {

    public Money tieredCharge(Plan plan, String meter, long quantity) {
        List<MeterTier> tiers = plan.meterTiers().get(meter);
        if (tiers == null || quantity <= 0) {
            return Money.zero();
        }
        Money charge = Money.zero();
        long consumed = 0;
        for (MeterTier tier : tiers) {
            if (consumed >= quantity) {
                break;
            }
            // 本档可承接量 = min(剩余用量, 档顶 - 已消费基线)；档顶 null 表示无上限。
            long headroom = tier.to() == null ? quantity - consumed : tier.to() - consumed;
            long inTier = Math.max(0, Math.min(quantity - consumed, headroom));
            if (inTier > 0) {
                charge = charge.plus(new Money(BigDecimal.valueOf(inTier).multiply(tier.unitPrice())));
                consumed += inTier;
            }
        }
        return charge;
    }
}
