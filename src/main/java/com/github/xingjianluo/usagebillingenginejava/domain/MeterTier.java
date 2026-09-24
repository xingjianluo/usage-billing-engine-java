package com.github.xingjianluo.usagebillingenginejava.domain;

import java.math.BigDecimal;

/**
 * 阶梯费率：用量位于 (from, to] 区间时，每单位单价 unitPrice。
 * to 为 null 表示无上限。示例：0->100 @0.10, 100->null @0.05。
 */
public record MeterTier(long from, Long to, BigDecimal unitPrice) {
}
