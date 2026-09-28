package com.github.xingjianluo.usagebillingenginejava.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 结算后补差单聚合。
 *
 * 一张补差单描述“某次出账之后、又落入同一账期的一批迟到用量”对该账期的净增影响：
 * amount = 用当前能看到的全部用量（含迟到部分）对整期重算出的应计总额
 *          − 该账期此前已经计过的金额（原账单净额 + 既往补差净额之和）。
 *
 * 幂等：以 (subscriptionId, period, contentKey) 为唯一约束，contentKey 由补差时
 * “该账期可见的全部用量事件 id 集合”内容寻址得到；同一批输入重放/并发只产生一张补差单。
 *
 * 原账单的编号、明细、金额永不被修改；补差单是独立单据，独立状态机、独立收款与入账。
 */
public class Adjustment {

    private final String id;
    private final String subscriptionId;
    private final BillingPeriod period;
    /** 触发本次补差所对应的原账单（不可变）。 */
    private final String invoiceId;
    private final String contentKey;
    private final Money amount;
    /** 重算所依据的全部用量事件 id（原账期用量 + 全部迟到用量）。 */
    private final List<String> basisEventIds;
    /** 账期内补差单序号（从 1 开始，按生成顺序）。 */
    private final int sequence;
    private final Instant createdAt;
    private AdjustmentStatus status;
    private int attemptCount;
    private PaymentFailureReason lastFailureReason;
    private String paymentReference;
    private Instant updatedAt;

    public Adjustment(String id, String subscriptionId, BillingPeriod period,
                      String invoiceId, String contentKey, Money amount,
                      List<String> basisEventIds, int sequence, Instant createdAt) {
        this.id = id;
        this.subscriptionId = subscriptionId;
        this.period = period;
        this.invoiceId = invoiceId;
        this.contentKey = contentKey;
        this.amount = amount;
        this.basisEventIds = new ArrayList<>(basisEventIds);
        this.sequence = sequence;
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

    public String contentKey() {
        return contentKey;
    }

    public Money amount() {
        return amount;
    }

    public List<String> basisEventIds() {
        return Collections.unmodifiableList(basisEventIds);
    }

    public int sequence() {
        return sequence;
    }

    public Instant createdAt() {
        return createdAt;
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
