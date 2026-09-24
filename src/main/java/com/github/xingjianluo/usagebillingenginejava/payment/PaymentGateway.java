package com.github.xingjianluo.usagebillingenginejava.payment;

import com.github.xingjianluo.usagebillingenginejava.model.Invoice;
import com.github.xingjianluo.usagebillingenginejava.model.PaymentFailureReason;

/** 支付通道抽象。 */
public interface PaymentGateway {
    PaymentFailureReason charge(Invoice invoice, int attemptNo);
}
