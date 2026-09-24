package com.github.xingjianluo.usagebillingenginejava.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 发票聚合。每张发票由 (subscriptionId, period) 唯一确定（仓储保证唯一约束）。
 * 状态流转见 InvoiceStatus。金额由各行合计得到，信用行为负。
 */
public class Invoice {

    private final String id;
    private final String subscriptionId;
    private final BillingPeriod period;
    private final List<InvoiceLineItem> lines = new ArrayList<>();
    private final Instant createdAt;
    private InvoiceStatus status;
    private int attemptCount;
    private PaymentFailureReason lastFailureReason;
    private String paymentReference;
    private Instant updatedAt;

    public Invoice(String id, String subscriptionId, BillingPeriod period,
                   List<InvoiceLineItem> lines, Instant createdAt) {
        this.id = id;
        this.subscriptionId = subscriptionId;
        this.period = period;
        this.lines.addAll(lines);
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
        this.status = InvoiceStatus.DRAFT;
        this.lastFailureReason = PaymentFailureReason.NONE;
    }

    public String id() {
        return id;
    }

    public String subscriptionId() {
        return subscriptionId;
    }

    public BillingPeriod period() {
        return period;
    }

    public List<InvoiceLineItem> lines() {
        return List.copyOf(lines);
    }

    public InvoiceStatus status() {
        return status;
    }

    public int attemptCount() {
        return attemptCount;
    }

    public PaymentFailureReason lastFailureReason() {
        return lastFailureReason;
    }

    public String paymentReference() {
        return paymentReference;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public Money total() {
        Money sum = Money.zero();
        for (InvoiceLineItem line : lines) {
            sum = line.credit() ? sum.minus(line.amount()) : sum.plus(line.amount());
        }
        return sum;
    }

    /** 供服务层在持锁状态下推进状态机。 */
    public void transitionTo(InvoiceStatus next, Instant now) {
        this.status = next;
        this.updatedAt = now;
    }

    /** 供服务层登记一次支付尝试（成功或失败）。 */
    public void recordPaymentResult(boolean success, PaymentFailureReason reason,
                                    String reference, Instant now) {
        this.attemptCount++;
        this.paymentReference = reference;
        this.lastFailureReason = success ? PaymentFailureReason.NONE : reason;
        this.updatedAt = now;
    }
}
