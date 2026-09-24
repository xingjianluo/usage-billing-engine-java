package com.github.xingjianluo.usagebillingenginejava.web.dto;

/** period 为该周期内任意日期（yyyy-MM-dd），可空表示当前周期。 */
public record IssueInvoiceRequest(String period) {
}
