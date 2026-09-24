package com.github.xingjianluo.usagebillingenginejava.payment;

import com.github.xingjianluo.usagebillingenginejava.model.Invoice;
import com.github.xingjianluo.usagebillingenginejava.model.PaymentFailureReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地模拟支付通道，不依赖任何外部服务或真实凭据。
 *
 * 行为约定：
 * - 未配置脚本的账号：一律扣款成功，返回 NONE；
 * - 配置脚本后：第 n 次尝试（n 从 1 起）取脚本第 min(n,len)-1 个结果，
 *   NONE 表示成功，其余为具体失败原因。最后一个结果持续生效，
 *   因此“前两次失败、第三次成功”与“永远失败”都可确定性地编排；
 * - 脚本按账号隔离，测试之间互不影响，并可通过 clear 复位。
 */
@Component
public class LocalPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(LocalPaymentGateway.class);

    private final Map<String, List<PaymentFailureReason>> scripts = new ConcurrentHashMap<>();

    public void script(String accountId, PaymentFailureReason... outcomes) {
        if (outcomes == null || outcomes.length == 0) {
            throw new IllegalArgumentException("支付脚本至少需要一个结果");
        }
        scripts.put(accountId, List.of(outcomes));
    }

    public void clear(String accountId) {
        scripts.remove(accountId);
    }

    public void clearAll() {
        scripts.clear();
    }

    @Override
    public PaymentFailureReason charge(Invoice invoice, int attemptNo) {
        List<PaymentFailureReason> script = scripts.get(invoice.accountId());
        PaymentFailureReason result;
        if (script == null || script.isEmpty()) {
            result = PaymentFailureReason.NONE;
        } else {
            int index = Math.min(attemptNo, script.size()) - 1;
            result = script.get(index);
        }
        if (result == PaymentFailureReason.NONE) {
            log.info("模拟通道扣款成功 invoice={} account={} attempt={} amount={}",
                    invoice.invoiceId(), invoice.accountId(), attemptNo, invoice.amount());
        } else {
            log.warn("模拟通道扣款失败 invoice={} account={} attempt={} reason={}",
                    invoice.invoiceId(), invoice.accountId(), attemptNo, result);
        }
        return result;
    }
}
