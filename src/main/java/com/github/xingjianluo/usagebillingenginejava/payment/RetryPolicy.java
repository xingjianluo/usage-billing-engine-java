package com.github.xingjianluo.usagebillingenginejava.payment;

import com.github.xingjianluo.usagebillingenginejava.model.PaymentFailureReason;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 支付重试策略（纯规则，无真实等待）。
 *
 * 失败原因区分：
 * - INSUFFICIENT_FUNDS / CARD_DECLINED：业务性拒绝，可在同一周期内有限重试；
 * - GATEWAY_ERROR：通道瞬时错误，可重试，采用指数退避；
 * - NONE：成功。
 *
 * 达到 {@value #MAX_ATTEMPTS} 次仍失败即进入 FAILED 终态，
 * 终态后任何 pay/retry 请求都被拒绝，发票不会静默丢失也不会重复入账。
 */
@Component
public class RetryPolicy {

    public static final int MAX_ATTEMPTS = 3;

    private static final Set<PaymentFailureReason> RETRYABLE = Set.of(
            PaymentFailureReason.INSUFFICIENT_FUNDS,
            PaymentFailureReason.CARD_DECLINED,
            PaymentFailureReason.GATEWAY_ERROR);

    public boolean retryable(PaymentFailureReason reason) {
        return reason != null && RETRYABLE.contains(reason);
    }

    /** 第 attemptNo 次失败后的退避秒数：2^(attemptNo-1)，仅用于展示/调度，测试不等待。 */
    public long backoffSeconds(int attemptNo) {
        if (attemptNo <= 0) {
            return 0L;
        }
        return 1L << (attemptNo - 1);
    }

    public boolean exhausted(int attemptCount) {
        return attemptCount >= MAX_ATTEMPTS;
    }
}
