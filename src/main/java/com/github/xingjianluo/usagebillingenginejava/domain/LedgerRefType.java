package com.github.xingjianluo.usagebillingenginejava.domain;

/**
 * 账本分录关联的单据类型：
 * INVOICE   —— 原发票（支付正向行 CHARGE / 变更抵扣 CREDIT / 发票退款 REFUND）；
 * ADJUSTMENT —— 结算后补差单（补收入账 CHARGE / 补差退款 REFUND）。
 */
public enum LedgerRefType {
    INVOICE,
    ADJUSTMENT
}
