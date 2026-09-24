package com.github.xingjianluo.usagebillingenginejava.service.dto;

import com.github.xingjianluo.usagebillingenginejava.domain.InvoiceStatus;
import com.github.xingjianluo.usagebillingenginejava.domain.Money;
import com.github.xingjianluo.usagebillingenginejava.domain.PaymentFailureReason;

public record PaymentResult(String invoiceId, boolean success, PaymentFailureReason failureReason,
                            int attemptCount, InvoiceStatus invoiceStatus,
                            Money charge, Money credit, Money refund) {
}
