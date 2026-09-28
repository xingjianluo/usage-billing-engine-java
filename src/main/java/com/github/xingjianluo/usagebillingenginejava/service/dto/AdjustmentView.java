package com.github.xingjianluo.usagebillingenginejava.service.dto;

import com.github.xingjianluo.usagebillingenginejava.domain.Adjustment;
import com.github.xingjianluo.usagebillingenginejava.domain.PaymentFailureReason;
import java.util.List;

/**
 * 补差单只读视图。
 */
public record AdjustmentView(String adjustmentId, String subscriptionId, String periodStart,
                             String periodEnd, String invoiceId, String status, String amount,
                             int sequence, int attemptCount, PaymentFailureReason lastFailureReason,
                             String paymentReference, List<String> basisEventIds) {

    public static AdjustmentView from(Adjustment a) {
        return new AdjustmentView(a.id(), a.subscriptionId(),
                a.period().start().toString(), a.period().end().toString(), a.invoiceId(),
                a.status().name(), a.amount().toString(), a.sequence(), a.attemptCount(),
                a.lastFailureReason(), a.paymentReference(), a.basisEventIds());
    }
}
