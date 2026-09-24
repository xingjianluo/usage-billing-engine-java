package com.github.xingjianluo.usagebillingenginejava.domain;

/**
 * 发票行：type 区分 BASE_PRORATED（按比例基础费/抵扣）与 USAGE_TIER（阶梯用量费）。
 * credit=true 表示抵扣行（负数金额参与合计）。
 */
public record InvoiceLineItem(String type, String description, Money amount, boolean credit) {
}
