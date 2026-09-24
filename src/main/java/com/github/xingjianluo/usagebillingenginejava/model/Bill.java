package com.github.xingjianluo.usagebillingenginejava.model;

import java.time.Instant;
import java.util.List;

/** 一个账号一个计费周期的账单。 */
public record Bill(
        String accountId,
        Instant periodStart,
        Instant periodEnd,
        List<BillLine> lines,
        Money total) {
}
