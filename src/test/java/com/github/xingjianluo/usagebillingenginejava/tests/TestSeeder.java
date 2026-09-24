package com.github.xingjianluo.usagebillingenginejava.tests;

import com.github.xingjianluo.usagebillingenginejava.domain.MeterTier;
import com.github.xingjianluo.usagebillingenginejava.domain.Money;
import com.github.xingjianluo.usagebillingenginejava.domain.Plan;
import com.github.xingjianluo.usagebillingenginejava.repo.PlanRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 与生产 SeedData 一致的测试套餐种子。 */
@Component
public class TestSeeder {

    private final PlanRepository planRepository;

    public TestSeeder(PlanRepository planRepository) {
        this.planRepository = planRepository;
    }

    public void seed() {
        planRepository.save(new Plan("BASIC", "基础版", Money.of("100.00"),
                Map.of("api_calls", List.of(
                        new MeterTier(0L, 1000L, new BigDecimal("0.00")),
                        new MeterTier(1000L, null, new BigDecimal("0.02"))))));
        planRepository.save(new Plan("PRO", "专业版", Money.of("300.00"),
                Map.of("api_calls", List.of(
                        new MeterTier(0L, 5000L, new BigDecimal("0.00")),
                        new MeterTier(5000L, null, new BigDecimal("0.01"))))));
    }
}
