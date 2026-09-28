package com.github.xingjianluo.usagebillingenginejava.service.dto;

import com.github.xingjianluo.usagebillingenginejava.domain.Money;
import java.util.List;

/**
 * 补差结果视图。
 * created=false 表示本次未产生新补差单（无新用量/补差为零/幂等重放），此时 adjustmentId 回指既有单（可能为 null）。
 */
public record AdjustmentResult(String adjustmentId, String subscriptionId, String invoiceId,
                               String periodStart, String periodEnd, int sequenceNo,
                               String batchId, Money delta, Money recalculatedTotal,
                               Money previouslyBilled, String status, boolean created,
                               List<String> basisEventIds, String basisSummary, String reason) {
}
