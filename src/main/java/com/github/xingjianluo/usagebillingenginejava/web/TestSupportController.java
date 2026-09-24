package com.github.xingjianluo.usagebillingenginejava.web;

import com.github.xingjianluo.usagebillingenginejava.domain.PaymentFailureReason;
import com.github.xingjianluo.usagebillingenginejava.service.SimulatedPaymentGateway;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 测试支持接口（仅 test profile 注册）：为模拟支付通道配置确定性失败脚本。
 * outcomes 为每次扣款结果序列，NONE=成功；用尽后默认成功。
 */
@RestController
@RequestMapping("/api/test-support")
@Profile("test")
public class TestSupportController {

    private final SimulatedPaymentGateway gateway;

    public TestSupportController(SimulatedPaymentGateway gateway) {
        this.gateway = gateway;
    }

    @PostMapping("/payment-script")
    public Map<String, Object> setScript(@RequestBody ScriptRequest request) {
        List<PaymentFailureReason> outcomes = request.outcomes() == null ? List.of()
                : request.outcomes().stream().map(PaymentFailureReason::valueOf).toList();
        gateway.setScript(request.accountId(), outcomes);
        return Map.of("accountId", request.accountId(), "outcomes", outcomes);
    }

    public record ScriptRequest(String accountId, List<String> outcomes) {
    }
}
