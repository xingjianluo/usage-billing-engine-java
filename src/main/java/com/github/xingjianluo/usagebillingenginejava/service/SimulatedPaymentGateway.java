package com.github.xingjianluo.usagebillingenginejava.service;

import com.github.xingjianluo.usagebillingenginejava.domain.Money;
import com.github.xingjianluo.usagebillingenginejava.domain.PaymentFailureReason;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 本地模拟支付通道。
 * - 通过 {@link #setScript} 为账户配置一串确定性结果（如先两次余额不足，第三次成功），
 *   用于复现“失败 -> 可区分原因 -> 重试 -> 成功/终态”的完整链路；
 * - 未配置脚本的账户默认成功；
 * - 以 idempotencyKey 去重：同一键重放返回同一结果与同一通道流水号，
 *   与真实通道的幂等语义一致，杜绝重试造成的重复扣款。
 */
@Component
public class SimulatedPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(SimulatedPaymentGateway.class);

    private final Map<String, List<PaymentFailureReason>> scripts = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> indexes = new ConcurrentHashMap<>();
    private final Map<String, PaymentGatewayResponse> idempotency = new ConcurrentHashMap<>();

    /** 配置账户脚本：序列元素为每次扣款结果，NONE 表示成功；用尽后默认成功。 */
    public void setScript(String accountId, List<PaymentFailureReason> outcomes) {
        scripts.put(accountId, List.copyOf(outcomes));
        indexes.put(accountId, new AtomicInteger());
    }

    public void clearScripts() {
        scripts.clear();
        indexes.clear();
        idempotency.clear();
    }

    @Override
    public PaymentGatewayResponse charge(String accountId, Money amount, String idempotencyKey) {
        PaymentGatewayResponse cached = idempotency.get(idempotencyKey);
        if (cached != null) {
            log.info("模拟通道幂等命中: accountId={}, key={}, success={}, ref={}",
                    accountId, idempotencyKey, cached.success(), cached.reference());
            return cached;
        }
        List<PaymentFailureReason> script = scripts.get(accountId);
        PaymentFailureReason outcome = PaymentFailureReason.NONE;
        if (script != null) {
            int i = indexes.get(accountId).getAndIncrement();
            outcome = i < script.size() ? script.get(i) : PaymentFailureReason.NONE;
        }
        boolean success = outcome == PaymentFailureReason.NONE;
        String reference = success ? "GW-" + idempotencyKey : null;
        PaymentGatewayResponse response = new PaymentGatewayResponse(success,
                success ? PaymentFailureReason.NONE : outcome, reference);
        idempotency.put(idempotencyKey, response);
        log.info("模拟通道扣款: accountId={}, amount={}, key={}, success={}, reason={}, ref={}",
                accountId, amount, idempotencyKey, success, outcome, reference);
        return response;
    }
}
