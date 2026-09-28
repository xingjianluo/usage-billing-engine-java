package com.github.xingjianluo.usagebillingenginejava.service.dto;

import com.github.xingjianluo.usagebillingenginejava.domain.Adjustment;
import com.github.xingjianluo.usagebillingenginejava.domain.Money;
import com.github.xingjianluo.usagebillingenginejava.domain.PaymentFailureReason;
import java.util.List;

/** 补差单只读视图。 */
public record AdjustmentView(String adjustmentId, String subscriptionId, String invoiceId,
                             String periodStart, String periodEnd, int sequenceNo, String batchId,
                             Money delta, Money recalculatedTotal, Money previouslyBilled,
                             String status, int attemptCount, PaymentFailureReason lastFailureReason,
                             String paymentReference, List<String> basisEventIds,
                             String basisSummary) {

    public static AdjustmentView from(Adjustment a) {
        return new AdjustmentView(a.id(), a.subscriptionId(), a.invoiceId(),
                a.period().start().toString(), a.period().end().toString(), a.sequenceNo(),
                a.batchId(), a.delta(), a.recalculatedTotal(), a.previouslyBilled(),
                a.status().name(), a.attemptCount(), a.lastFailureReason(), a.paymentReference(),
                a.basisEventIds(), a.basisSummary());
    }
}
