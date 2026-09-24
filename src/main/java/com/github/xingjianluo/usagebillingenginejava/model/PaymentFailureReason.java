package com.github.xingjianluo.usagebillingenginejava.model;

/** 支付失败原因，要求可区分、可解释。 */
public enum PaymentFailureReason {
    NONE,
    INSUFFICIENT_FUNDS,
    CARD_DECLINED,
    GATEWAY_ERROR
}
