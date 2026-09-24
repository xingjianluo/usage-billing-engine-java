package com.github.xingjianluo.usagebillingenginejava.domain;

import java.time.LocalDate;

/**
 * 计费周期：自然月 [start, end)，end 为下月 1 日。
 */
public record BillingPeriod(LocalDate start, LocalDate end) {
}
