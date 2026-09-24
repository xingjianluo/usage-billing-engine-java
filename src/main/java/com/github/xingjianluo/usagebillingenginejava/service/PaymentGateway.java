package com.github.xingjianluo.usagebillingenginejava.service;

import com.github.xingjianluo.usagebillingenginejava.domain.Money;
import com.github.xingjianluo.usagebillingenginejava.domain.PaymentFailureReason;

/**
 * 本地模拟支付通道，不依赖任何外部服务。
 */
public interface PaymentGateway {

    PaymentGatewayResponse charge(String accountId, Money amount, String idempotencyKey);

    record PaymentGatewayResponse(boolean success, PaymentFailureReason failureReason,
                                  String reference) {
    }
}
