package com.github.xingjianluo.usagebillingenginejava.domain;

/**
 * 发票状态机：
 * DRAFT -> OPEN（开票）-> PAID（支付成功，终态）
 *                    \-> PAYMENT_FAILED -> RETRYING -> OPEN ...（重试中）
 *                    \-> VOID（作废，终态）
 * RETRYING -> MAX_RETRIES_EXCEEDED（终态）
 */
public enum InvoiceStatus {
    DRAFT,
    OPEN,
    PAYMENT_FAILED,
    RETRYING,
    PAID,
    MAX_RETRIES_EXCEEDED,
    VOID
}
