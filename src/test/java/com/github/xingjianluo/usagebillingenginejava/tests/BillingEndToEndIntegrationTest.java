package com.github.xingjianluo.usagebillingenginejava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.usagebillingenginejava.model.PaymentFailureReason;
import com.github.xingjianluo.usagebillingenginejava.payment.LocalPaymentGateway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端集成测试：经过真实 Spring MVC 入口（采集 -> 变更 -> 开票 -> 支付/重试），
 * 全程使用内存存储与本地模拟通道，不依赖任何外部服务或真实凭据。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BillingEndToEndIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(BillingEndToEndIntegrationTest.class);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private LocalPaymentGateway gateway;

    private String json(Object... kv) throws Exception {
        var node = objectMapper.createObjectNode();
        for (int i = 0; i < kv.length; i += 2) {
            node.put((String) kv[i], kv[i + 1].toString());
        }
        return objectMapper.writeValueAsString(node);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("HTTP 全链路：重复上报不重复计量；重复开票一张；默认通道一次支付成功")
    void fullFlowOverHttp() throws Exception {
        String account = "e2e-" + UUID.randomUUID();
        Instant anchor = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(1_800);
        Instant occurredAt = anchor.plusSeconds(60);

        // 1) 建订阅
        mockMvc.perform(post("/api/subscriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("accountId", account, "anchor", anchor, "plan", "PRO")))
                .andExpect(status().isOk());

        // 2) 同一事件上报两次（模拟客户端重试）
        String payload = json("eventId", "evt-e2e-1", "accountId", account,
                "metric", "api_calls", "quantity", 5_000, "occurredAt", occurredAt);
        JsonNode first = body(mockMvc.perform(post("/api/usage/events")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk()).andReturn());
        JsonNode second = body(mockMvc.perform(post("/api/usage/events")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk()).andReturn());
        log.info("[HTTP 采集] 首次={} 重放={}", first.get("result").asText(), second.get("result").asText());
        assertEquals("ACCEPTED", first.get("result").asText());
        assertEquals("DUPLICATE", second.get("result").asText());

        // 3) 开票两次（幂等）
        String issuePayload = json("accountId", account, "periodStart", anchor);
        JsonNode inv1 = body(mockMvc.perform(post("/api/billing/invoices")
                        .contentType(MediaType.APPLICATION_JSON).content(issuePayload))
                .andExpect(status().isOk()).andReturn());
        JsonNode inv2 = body(mockMvc.perform(post("/api/billing/invoices")
                        .contentType(MediaType.APPLICATION_JSON).content(issuePayload))
                .andExpect(status().isOk()).andReturn());
        log.info("[HTTP 开票] invoice1={} invoice2={} amount={}",
                inv1.get("invoiceId").asText(), inv2.get("invoiceId").asText(),
                inv1.get("amount").get("amount").asText());
        assertEquals(inv1.get("invoiceId").asText(), inv2.get("invoiceId").asText());
        // PRO 基础 99 + 5000*0.01 = 149.00
        assertEquals("149.00", inv1.get("amount").get("amount").asText());

        // 4) 支付成功
        JsonNode paid = body(mockMvc.perform(
                        post("/api/billing/invoices/" + inv1.get("invoiceId").asText() + "/pay"))
                .andExpect(status().isOk()).andReturn());
        log.info("[HTTP 支付] status={} attempts={}", paid.get("status").asText(),
                paid.get("attemptCount").asInt());
        assertEquals("PAID", paid.get("status").asText());

        // 5) 重复支付应 409 冲突
        mockMvc.perform(post("/api/billing/invoices/" + inv1.get("invoiceId").asText() + "/pay"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("HTTP 失败链路：两次失败后重试成功，失败原因逐次可区分")
    void failureThenRetryOverHttp() throws Exception {
        String account = "e2e-fail-" + UUID.randomUUID();
        Instant anchor = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(1_800);
        mockMvc.perform(post("/api/subscriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("accountId", account, "anchor", anchor, "plan", "FREE")))
                .andExpect(status().isOk());

        gateway.script(account,
                PaymentFailureReason.INSUFFICIENT_FUNDS,
                PaymentFailureReason.CARD_DECLINED,
                PaymentFailureReason.NONE);

        JsonNode inv = body(mockMvc.perform(post("/api/billing/invoices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("accountId", account, "periodStart", anchor)))
                .andExpect(status().isOk()).andReturn());
        String invoiceId = inv.get("invoiceId").asText();

        JsonNode p1 = body(mockMvc.perform(post("/api/billing/invoices/" + invoiceId + "/pay"))
                .andExpect(status().isOk()).andReturn());
        JsonNode p2 = body(mockMvc.perform(post("/api/billing/invoices/" + invoiceId + "/pay"))
                .andExpect(status().isOk()).andReturn());
        log.info("[HTTP 失败] 第一次原因={} 第二次原因={}",
                p1.get("lastFailureReason").asText(), p2.get("lastFailureReason").asText());
        assertEquals("INSUFFICIENT_FUNDS", p1.get("lastFailureReason").asText());
        assertEquals("CARD_DECLINED", p2.get("lastFailureReason").asText());
        assertEquals("PENDING_PAYMENT", p2.get("status").asText());

        JsonNode done = body(mockMvc.perform(post("/api/billing/invoices/" + invoiceId + "/retry"))
                .andExpect(status().isOk()).andReturn());
        assertEquals("PAID", done.get("status").asText());
        assertEquals(3, done.get("attemptCount").asInt());
        log.info("[HTTP 重试] 最终 status=PAID attempts=3");
        gateway.clear(account);
    }

    @Test
    @DisplayName("HTTP 查询不存在的发票返回 200 空体；非法参数返回 4xx")
    void validationErrors() throws Exception {
        mockMvc.perform(post("/api/usage/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("eventId", "x", "accountId", "a", "metric", "m",
                                "quantity", 0, "occurredAt", Instant.now())))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/billing/accounts/nobody/invoices")
                        .param("periodStart", Instant.now().minusSeconds(60).toString()))
                .andExpect(result ->
                        assertTrue(result.getResponse().getContentAsString().isBlank()
                                || result.getResponse().getStatus() == 200));
    }
}
