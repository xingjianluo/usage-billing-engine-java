package com.github.xingjianluo.usagebillingenginejava.model;

import java.math.BigDecimal;

/** 阶梯单价：[from, to) 区间内每单位价格；to 为 null 表示无上限。 */
public record Tier(long fromInclusive, Long toExclusive, BigDecimal unitPrice) {
}
