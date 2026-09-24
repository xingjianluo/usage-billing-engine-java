package com.github.xingjianluo.usagebillingenginejava.model;

import java.time.Instant;
import java.util.List;

/** 发票：同一账号同一周期幂等，只可能存在一张。 */
public record Invoice(
        String invoiceId,
        String accountId,
        Instant periodStart,
        Instant periodEnd,
        Money amount,
        InvoiceStatus status,
        PaymentFailureReason lastFailureReason,
        int attemptCount,
        List<PaymentAttempt> attempts,
        Instant createdAt,
        Instant updatedAt) {
}
