package com.github.xingjianluo.usagebillingenginejava.tests;

import com.github.xingjianluo.usagebillingenginejava.metering.TierRatingService;
import com.github.xingjianluo.usagebillingenginejava.model.MetricRate;
import com.github.xingjianluo.usagebillingenginejava.model.Money;
import com.github.xingjianluo.usagebillingenginejava.model.Tier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 阶梯费率单元测试：验证边际单价切片与“同输入重放金额可复现”。
 */
class TierRatingServiceTest {

    private static final Logger log = LoggerFactory.getLogger(TierRatingServiceTest.class);

    private final TierRatingService service = new TierRatingService();

    @Test
    @DisplayName("阶梯切片：前1000免费，其后0.02；跨档数量分段计价")
    void ratesAcrossTiers() {
        MetricRate rate = new MetricRate("api_calls", java.util.List.of(
                new Tier(0L, 1_000L, new BigDecimal("0.00")),
                new Tier(1_000L, null, new BigDecimal("0.02"))));

        long input = 2_500L;
        Money money = service.rate(input, rate);
        // 1000*0 + 1500*0.02 = 30.00
        log.info("[关键输入] quantity={} tiers=[0-1000@0,>1000@0.02] [判定依据] 跨档 1500 单位 × 0.02",
                input);
        assertEquals(Money.of(30), money);

        assertEquals(Money.of(20), service.rate(2_000, rate));
        assertEquals(Money.ZERO, service.rate(999, rate));
        assertEquals(Money.ZERO, service.rate(1_000, rate));
    }

    @Test
    @DisplayName("可复现：同一输入重复计算100次金额逐分相同")
    void replayIsDeterministic() {
        MetricRate rate = new MetricRate("storage_gb", java.util.List.of(
                new Tier(0L, 100L, new BigDecimal("0.05")),
                new Tier(100L, null, new BigDecimal("0.08"))));
        Money first = service.rate(150, rate); // 100*0.05 + 50*0.08 = 9.00
        for (int i = 0; i < 100; i++) {
            Money again = service.rate(150, rate);
            assertEquals(first, again, "第 " + i + " 次重放金额不一致");
        }
        log.info("[关键输入] quantity=150 重复100次 [判定依据] 纯函数无副作用，结论 {} 恒定", first);
        assertEquals(new Money(new BigDecimal("9.00")), first);
    }
}
