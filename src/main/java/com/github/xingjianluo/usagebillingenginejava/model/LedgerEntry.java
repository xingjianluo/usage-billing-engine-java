package com.github.xingjianluo.usagebillingenginejava.model;

import java.time.Instant;

/** 不可变账本流水：正数入账，负数为退款/冲正。 */
public record LedgerEntry(
        String entryId,
        String invoiceId,
        String accountId,
        Money amount,
        String type,
        Instant createdAt) {
}
