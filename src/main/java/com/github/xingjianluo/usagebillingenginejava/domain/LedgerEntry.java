package com.github.xingjianluo.usagebillingenginejava.domain;

import java.time.Instant;

/**
 * 账本分录。金额一律为非负，方向由 type 表达：
 * CHARGE=入账（向客户收取），CREDIT=信用抵扣，REFUND=退款。
 * refId 关联的单据号（原发票或补差单）由 refType 区分，便于按单对账。
 *
 * 守恒（单订阅维度，账期全部关闭时）：SUM(CHARGE) == SUM(CREDIT) + SUM(REFUND)。
 */
public record LedgerEntry(String id, String subscriptionId, LedgerEntryType type,
                          Money amount, String refId, LedgerRefType refType,
                          String reason, Instant createdAt) {

    /** 兼容原发票分录的便捷构造：refType=INVOICE。 */
    public LedgerEntry(String id, String subscriptionId, LedgerEntryType type,
                       Money amount, String invoiceId, String reason, Instant createdAt) {
        this(id, subscriptionId, type, amount, invoiceId, LedgerRefType.INVOICE, reason, createdAt);
    }
}
