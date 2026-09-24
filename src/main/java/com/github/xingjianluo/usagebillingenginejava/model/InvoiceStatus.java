package com.github.xingjianluo.usagebillingenginejava.model;

/** 发票状态：草稿 -> 待支付 -> 支付成功/失败终态。 */
public enum InvoiceStatus {
    DRAFT,
    PENDING_PAYMENT,
    PAID,
    FAILED
}
