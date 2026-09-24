package com.github.xingjianluo.usagebillingenginejava.model;

/** 账单明细行。 */
public record BillLine(
        BillLineKind kind,
        String planCode,
        String metric,
        String description,
        long quantity,
        Money amount) {
}
