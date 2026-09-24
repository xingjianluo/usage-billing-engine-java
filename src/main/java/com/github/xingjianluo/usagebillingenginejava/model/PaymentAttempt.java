package com.github.xingjianluo.usagebillingenginejava.model;

import java.time.Instant;

/** 一次支付尝试及其结果。 */
public record PaymentAttempt(
        int attemptNo,
        Instant attemptedAt,
        boolean success,
        PaymentFailureReason failureReason) {
}
