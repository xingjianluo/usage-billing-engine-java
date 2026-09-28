package com.github.xingjianluo.usagebillingenginejava.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 结算后补差单聚合。
 *
 * 语义：账期发票已经开出（OPEN/PAID/PAYMENT_FAILED/VOID 任意状态）之后，又收到发生时间
 * 落在该账期内的迟到用量。补差单独立表达一次整期重算的净增结果，
 * <b>永不改写原发票</b>（编号、明细、金额都不变）。
 *
 * 金额口径：
 *   recalculatedTotal = 用“当前能看到的全部用量（含迟到部分）”对整期重新算出的应计总额；
 *   previouslyBilled  = 原发票净额 + 该账期此前所有补差净额（此前已计金额）；
 *   delta             = recalculatedTotal − previouslyBilled（正常补收为非负）。
 * 望远镜式相减保证：原账单净额 + Σ各补差 delta == 最终全部用量重算的应计总额。
 *
 * 收款口径与发票一致：补差单只在收款成功后由账本登一笔 CHARGE；失败可重试，
 * 已支付的补差单可退款（REFUNDED）。
 */
public class Adjustment {

    private final String id;
    private final String subscriptionId;
    private final BillingPeriod period;
    private final String invoiceId;
    private final int sequenceNo;
    private final String batchId;
    private final Money delta;
    private final Money recalculatedTotal;
    private final Money previouslyBilled;
    private final List<String> basisEventIds;
    private final String basisSummary;
    private final Instant createdAt;
    private AdjustmentStatus status;
    private int attemptCount;
    private PaymentFailureReason lastFailureReason;
    private String paymentReference;
    private Instant updatedAt;

    public Adjustment(String id, String subscriptionId, BillingPeriod period, String invoiceId,
                      int sequenceNo, String batchId, Money delta, Money recalculatedTotal,
                      Money previouslyBilled, List<String> basisEventIds, String basisSummary,
                      Instant createdAt) {
        this.id = id;
        this.subscriptionId = subscriptionId;
        this.period = period;
        this.invoiceId = invoiceId;
        this.sequenceNo = sequenceNo;
        this.batchId = batchId;
        this.delta = delta;
        this.recalculatedTotal = recalculatedTotal;
        this.previouslyBilled = previouslyBilled;
        this.basisEventIds = new ArrayList<>(basisEventIds);
        this.basisSummary = basisSummary;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
        this.status = AdjustmentStatus.OPEN;
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

    public String invoiceId() {
        return invoiceId;
    }

    public int sequenceNo() {
        return sequenceNo;
    }

    public String batchId() {
        return batchId;
    }

    public Money delta() {
        return delta;
    }

    public Money recalculatedTotal() {
        return recalculatedTotal;
    }

    public Money previouslyBilled() {
        return previouslyBilled;
    }

    public List<String> basisEventIds() {
        return List.copyOf(basisEventIds);
    }

    public String basisSummary() {
        return basisSummary;
    }

    public AdjustmentStatus status() {
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

    /** 供服务层在持锁状态下推进状态机。 */
    public void transitionTo(AdjustmentStatus next, Instant now) {
        this.status = next;
        this.updatedAt = now;
    }

    /** 供服务层登记一次收款尝试（成功或失败）。 */
    public void recordPaymentResult(boolean success, PaymentFailureReason reason,
                                    String reference, Instant now) {
        this.attemptCount++;
        this.paymentReference = reference;
        this.lastFailureReason = success ? PaymentFailureReason.NONE : reason;
        this.updatedAt = now;
    }
}
