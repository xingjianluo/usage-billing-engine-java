package com.github.xingjianluo.usagebillingenginejava.service.dto;

import com.github.xingjianluo.usagebillingenginejava.domain.InvoiceLineItem;
import com.github.xingjianluo.usagebillingenginejava.domain.Money;
import java.util.List;
import java.util.Map;

/**
 * 整期账单重算结果（开票与结算后补差共用同一口径）。
 * netTotal 为各行带符号合计；meterTotalsBySegment 为“套餐片 -> (meter -> 累计量)”，作为判定依据；
 * basisEventIds 为本周期计入的全部事件（eventId 升序），用于补差幂等与可解释。
 */
public record BillingCalculation(List<InvoiceLineItem> lines, Money netTotal,
                                 Map<String, Map<String, Long>> meterTotalsBySegment,
                                 List<String> basisEventIds) {
}
