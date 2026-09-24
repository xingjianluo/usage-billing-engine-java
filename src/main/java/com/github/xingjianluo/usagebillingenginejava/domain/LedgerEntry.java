package com.github.xingjianluo.usagebillingenginejava.domain;

import java.time.Instant;

/**
 * 账本分录。金额一律为非负，方向由 type 表达：
 * CHARGE=入账（向客户收取），CREDIT=信用抵扣，REFUND=退款。
 * 每笔分录可关联发票号，便于对账。
 * 守恒（单订阅维度）：SUM(CHARGE) == SUM(CREDIT) + SUM(REFUND)。
 */
public record LedgerEntry(String id, String subscriptionId, LedgerEntryType type,
                          Money amount, String invoiceId, String reason, Instant createdAt) {
}
