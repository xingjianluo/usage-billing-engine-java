package com.github.xingjianluo.usagebillingenginejava.service.dto;

import com.github.xingjianluo.usagebillingenginejava.domain.AdjustmentStatus;
import com.github.xingjianluo.usagebillingenginejava.domain.Money;
import com.github.xingjianluo.usagebillingenginejava.domain.PaymentFailureReason;

/** 补差单收款/退款结果视图（字段口径与 PaymentResult 一致）。 */
public record AdjustmentPaymentResult(String adjustmentId, boolean success,
                                      PaymentFailureReason failureReason, int attemptCount,
                                      AdjustmentStatus status, Money delta,
                                      Money charge, Money refund) {
}
