package com.github.xingjianluo.usagebillingenginejava.domain;

import java.util.List;
import java.util.Map;

/**
 * 套餐：基础月租 basePrice + 各计量项 meterTiers 的阶梯费率（按量后付费）。
 */
public record Plan(String code, String name, Money basePrice,
                   Map<String, List<MeterTier>> meterTiers) {
}
