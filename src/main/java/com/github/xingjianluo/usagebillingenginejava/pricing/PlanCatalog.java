package com.github.xingjianluo.usagebillingenginejava.pricing;

import com.github.xingjianluo.usagebillingenginejava.model.MetricRate;
import com.github.xingjianluo.usagebillingenginejava.model.Money;
import com.github.xingjianluo.usagebillingenginejava.model.Plan;
import com.github.xingjianluo.usagebillingenginejava.model.Tier;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 套餐目录（本地模拟，进程内常量）。
 *
 * 阶梯模型（边际单价）：每个计量项按 [from, to) 定义单价区间，
 * 用量落在不同区间的部分分别计价后求和——用量越高边际单价越低。
 */
@Component
public class PlanCatalog {

    public static final String FREE = "FREE";
    public static final String PRO = "PRO";
    public static final String ENTERPRISE = "ENTERPRISE";

    private final Map<String, Plan> plans = new LinkedHashMap<>();

    public PlanCatalog() {
        // FREE：无基础费；api_calls 前 1000 次免费，之后 0.02/次
        register(new Plan(FREE, Money.ZERO, List.of(
                new MetricRate("api_calls", List.of(
                        new Tier(0L, 1_000L, new BigDecimal("0.00")),
                        new Tier(1_000L, null, new BigDecimal("0.02")))),
                new MetricRate("storage_gb", List.of(
                        new Tier(0L, null, new BigDecimal("0.10")))))));

        // PRO：基础费 99；api_calls 前 10000 次 0.01，超出 0.015
        register(new Plan(PRO, Money.of(99), List.of(
                new MetricRate("api_calls", List.of(
                        new Tier(0L, 10_000L, new BigDecimal("0.01")),
                        new Tier(10_000L, null, new BigDecimal("0.015")))),
                new MetricRate("storage_gb", List.of(
                        new Tier(0L, 100L, new BigDecimal("0.05")),
                        new Tier(100L, null, new BigDecimal("0.08")))))));

        // ENTERPRISE：基础费 299；api_calls 一口价 0.008
        register(new Plan(ENTERPRISE, Money.of(299), List.of(
                new MetricRate("api_calls", List.of(
                        new Tier(0L, null, new BigDecimal("0.008")))),
                new MetricRate("storage_gb", List.of(
                        new Tier(0L, null, new BigDecimal("0.03")))))));
    }

    private void register(Plan plan) {
        plans.put(plan.code(), plan);
    }

    public Optional<Plan> find(String code) {
        return Optional.ofNullable(plans.get(code));
    }

    public Plan require(String code) {
        return find(code).orElseThrow(() -> new IllegalArgumentException("未知套餐: " + code));
    }

    public List<Plan> all() {
        return List.copyOf(plans.values());
    }
}
