package com.github.xingjianluo.usagebillingenginejava.service.dto;

import com.github.xingjianluo.usagebillingenginejava.domain.AdjustmentStatus;
import com.github.xingjianluo.usagebillingenginejava.domain.Money;
import java.util.List;

/**
 * 补差处理结果。created=false 表示同内容批次已处理过（并发/重放），返回既有补差单。
 */
public record AdjustmentResult(String adjustmentId, String subscriptionId,
                               String periodStart, String periodEnd,
                               String invoiceId, Money recalculatedTotal, Money previouslyBilled,
                               Money amount, AdjustmentStatus status, boolean created,
                               List<String> basisEventIds, String reason) {
}
