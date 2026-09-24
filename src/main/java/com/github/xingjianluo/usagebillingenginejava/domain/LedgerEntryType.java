package com.github.xingjianluo.usagebillingenginejava.domain;

/**
 * 账本分录类型。金额符号约定：
 * CHARGE / REFUND 均以正数金额记录，由类型表达借贷方向；
 * 守恒式：CHARGE 合计 = CREDIT 合计 + REFUND 合计
 * （CREDIT 为变更时按比例产生的抵扣，最终未被消费的 CREDIT 在退款时转为 REFUND）。
 */
public enum LedgerEntryType {
    CHARGE,
    CREDIT,
    REFUND
}
