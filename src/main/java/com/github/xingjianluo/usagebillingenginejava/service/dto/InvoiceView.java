package com.github.xingjianluo.usagebillingenginejava.service.dto;

import com.github.xingjianluo.usagebillingenginejava.domain.Invoice;
import com.github.xingjianluo.usagebillingenginejava.domain.Money;
import com.github.xingjianluo.usagebillingenginejava.domain.InvoiceLineItem;
import com.github.xingjianluo.usagebillingenginejava.domain.PaymentFailureReason;
import java.util.List;

/** 发票只读视图。 */
public record InvoiceView(String invoiceId, String subscriptionId, String periodStart,
                          String periodEnd, String status, Money total, int attemptCount,
                          PaymentFailureReason lastFailureReason, String paymentReference,
                          List<LineView> lines) {

    public record LineView(String type, String description, String amount, boolean credit) {
    }

    public static InvoiceView from(Invoice i) {
        List<LineView> lineViews = i.lines().stream()
                .map((InvoiceLineItem l) -> new LineView(l.type(), l.description(),
                        l.amount().toString(), l.credit()))
                .toList();
        return new InvoiceView(i.id(), i.subscriptionId(),
                i.period().start().toString(), i.period().end().toString(),
                i.status().name(), i.total(), i.attemptCount(),
                i.lastFailureReason(), i.paymentReference(), lineViews);
    }
}
