package com.github.xingjianluo.usagebillingenginejava.domain;

/**
 * 补差单状态机（口径与发票支付状态一致）：
 * OPEN -> PAID（收款成功，终态）/ PAYMENT_FAILED（可重试）
 * PAYMENT_FAILED --retry--> RETRYING --结果--> PAID / PAYMENT_FAILED / MAX_RETRIES_EXCEEDED
 * PAID --refund--> REFUNDED（终态）
 */
public enum AdjustmentStatus {
    OPEN,
    PAYMENT_FAILED,
    RETRYING,
    PAID,
    MAX_RETRIES_EXCEEDED,
    REFUNDED
}
