package com.github.xingjianluo.usagebillingenginejava.service.dto;

import com.github.xingjianluo.usagebillingenginejava.domain.InvoiceStatus;
import com.github.xingjianluo.usagebillingenginejava.domain.Money;

/**
 * 开票结果。created=false 表示发票已存在（并发/重试场景），返回既有发票，保证一周期一票。
 */
public record IssueInvoiceResult(String invoiceId, String subscriptionId,
                                 String periodStart, String periodEnd,
                                 Money total, InvoiceStatus status, boolean created) {
}
