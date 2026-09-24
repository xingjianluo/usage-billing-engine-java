package com.github.xingjianluo.usagebillingenginejava.model;

import java.util.List;

/**
 * 订阅套餐：周期基础费 + 各计量项的阶梯单价。
 * 套餐定义不可变，定价历史通过订阅上的 planChanges 保留，保证旧账单可复现。
 */
public record Plan(String code, Money baseFee, List<MetricRate> metricRates) {

    public Plan {
        metricRates = List.copyOf(metricRates);
    }

    /** 未配置单价的计量项视为零元（显式免费，避免漏计或误抛错）。 */
    public MetricRate rateOf(String metric) {
        return metricRates.stream()
                .filter(r -> r.metric().equals(metric))
                .findFirst()
                .orElseGet(() -> new MetricRate(metric, List.of()));
    }
}
