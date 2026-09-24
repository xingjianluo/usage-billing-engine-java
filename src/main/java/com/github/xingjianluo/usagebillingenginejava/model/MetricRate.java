package com.github.xingjianluo.usagebillingenginejava.model;

import java.util.List;

/** 某计量项的阶梯定价。 */
public record MetricRate(String metric, List<Tier> tiers) {
}
