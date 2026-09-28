package com.github.xingjianluo.usagebillingenginejava.domain;

/**
 * 补差单状态：
 * OPEN：补差已生成，尚未收款（原账单 OPEN/支付失败/已作废 时）；
 * PAYMENT_FAILED：补差收款失败，可重试；
 * PAID：补差金额为零（无需收款）或已收款成功并入账（终态）；
 * VOID：随原账单退款被冲回（终态）。
 */
public enum AdjustmentStatus {
    OPEN,
    PAYMENT_FAILED,
    PAID,
    VOID
}
