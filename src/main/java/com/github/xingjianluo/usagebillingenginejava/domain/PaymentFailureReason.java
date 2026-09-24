package com.github.xingjianluo.usagebillingenginejava.domain;

/**
 * 可区分的支付失败原因（模拟通道返回）。
 */
public enum PaymentFailureReason {
    NONE,
    INSUFFICIENT_FUNDS,
    CARD_DECLINED,
    GATEWAY_TIMEOUT,
    INVALID_ACCOUNT
}
