package com.github.xingjianluo.usagebillingenginejava.config;

import com.github.xingjianluo.usagebillingenginejava.domain.MeterTier;
import com.github.xingjianluo.usagebillingenginejava.domain.Money;
import com.github.xingjianluo.usagebillingenginejava.domain.Plan;
import com.github.xingjianluo.usagebillingenginejava.repo.PlanRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 预置套餐（本地模拟，无外部依赖）：
 * - BASIC  ：月租 100.00，API 调用 0~1000 免费、1000 以上 0.02/次；
 * - PRO    ：月租 300.00，API 调用 0~5000 免费、5000 以上 0.01/次。
 */
@Configuration
public class SeedData {

    @Bean
    public CommandLineRunner seedPlans(PlanRepository planRepository) {
        return args -> {
            Plan basic = new Plan("BASIC", "基础版", Money.of("100.00"),
                    Map.of("api_calls", List.of(
                            new MeterTier(0L, 1000L, new BigDecimal("0.00")),
                            new MeterTier(1000L, null, new BigDecimal("0.02")))));
            Plan pro = new Plan("PRO", "专业版", Money.of("300.00"),
                    Map.of("api_calls", List.of(
                            new MeterTier(0L, 5000L, new BigDecimal("0.00")),
                            new MeterTier(5000L, null, new BigDecimal("0.01")))));
            planRepository.save(basic);
            planRepository.save(pro);
        };
    }
}
