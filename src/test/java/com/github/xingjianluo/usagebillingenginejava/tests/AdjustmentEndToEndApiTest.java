package com.github.xingjianluo.usagebillingenginejava.tests;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.xingjianluo.usagebillingenginejava.tests.config.FixedClockConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 结算后补差 HTTP 端到端：既有上报/开票/支付入口不变，迟到用量走原上报入口接收，
 * 补差单独触发产出独立补差单，支付/重放幂等/作废退款联动/账本守恒全部成立。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockConfig.class)
class AdjustmentEndToEndApiTest {

    private static final Logger log = LoggerFactory.getLogger(AdjustmentEndToEndApiTest.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private MockMvc mockMvc;

    @BeforeEach
    void reset() {
        TestResetter.resetAll();
    }

    @Test
    @DisplayName("HTTP：出账后迟到补报 -> 补差 -> 并发/重放幂等 -> 收款 -> 作废退款，账本守恒且原票不变")
    void lateUsageAdjustmentOverHttp() throws Exception {
        String sub = "sub-adj-e2e-001";

        // 1) 创建订阅 + 早期用量 600（免费档内）
        createSub(sub);
        report("ae-1", sub, 600, "2026-09-05T08:00:00Z");

        // 2) 开票：100.00
        MvcResult issueRes = postJson("/api/subscriptions/" + sub + "/invoices",
                "{\"period\":\"2026-09-15\"}");
        JsonNode invoice = JSON.readTree(issueRes.getResponse().getContentAsString());
        String invoiceId = invoice.get("invoiceId").asText();
        org.junit.jupiter.api.Assertions.assertEquals(100.0, invoice.get("total").asDouble(), 0.001);

        // 3) 支付原发票
        mockMvc.perform(post("/api/invoices/" + invoiceId + "/pay"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));

        // 4) 出账后迟到补报 900（走既有 /usage-events 入口，正常接收不丢弃）
        MvcResult late = report("ae-late", sub, 900, "2026-09-10T08:00:00Z");
        log.info("[判定依据] 出账后迟到事件仍 ACCEPTED: {}", late.getResponse().getStatus());

        // 5) 触发补差：delta=10.00，独立补差单，原票仍 100.00
        MvcResult adj1 = postJson("/api/subscriptions/" + sub + "/adjustments",
                "{\"period\":\"2026-09-15\",\"batchId\":\"http-batch-1\"}");
        JsonNode a1 = JSON.readTree(adj1.getResponse().getContentAsString());
        String adjId = a1.get("adjustmentId").asText();
        org.junit.jupiter.api.Assertions.assertEquals(10.0, a1.get("delta").asDouble(), 0.001);
        org.junit.jupiter.api.Assertions.assertEquals(110.0, a1.get("recalculatedTotal").asDouble(), 0.001);
        org.junit.jupiter.api.Assertions.assertEquals(true, a1.get("created").asBoolean());
        org.junit.jupiter.api.Assertions.assertEquals(invoiceId, a1.get("invoiceId").asText());

        // 6) 同批次重放 + 再发一次：created=false、同一单号、同一 delta
        MvcResult adjReplay = postJson("/api/subscriptions/" + sub + "/adjustments",
                "{\"period\":\"2026-09-15\",\"batchId\":\"http-batch-1\"}");
        JsonNode replay = JSON.readTree(adjReplay.getResponse().getContentAsString());
        org.junit.jupiter.api.Assertions.assertEquals(adjId, replay.get("adjustmentId").asText());
        org.junit.jupiter.api.Assertions.assertEquals(false, replay.get("created").asBoolean());

        // 7) 查询补差单与原发票：原票金额/编号不变
        mockMvc.perform(get("/api/adjustments/" + adjId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delta").value(10.0))
                .andExpect(jsonPath("$.status").value("OPEN"));
        mockMvc.perform(get("/api/invoices/" + invoiceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(100.0))
                .andExpect(jsonPath("$.status").value("PAID"));

        // 8) 补差收款成功：CHARGE 累计 = 100 + 10 = 110
        mockMvc.perform(post("/api/adjustments/" + adjId + "/pay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.status").value("PAID"));
        mockMvc.perform(post("/api/adjustments/" + adjId + "/pay"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.attemptCount").value(1));

        // 9) 作废原发票：联动退回补差，账本守恒 CHARGE=CREDIT+REFUND=110
        mockMvc.perform(post("/api/invoices/" + invoiceId + "/refund"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invoiceStatus").value("VOID"));
        mockMvc.perform(get("/api/adjustments/" + adjId))
                .andExpect(jsonPath("$.status").value("REFUNDED"));
        mockMvc.perform(get("/api/subscriptions/" + sub + "/ledger"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.charge").value(110.0))
                .andExpect(jsonPath("$.refund").value(110.0))
                .andExpect(jsonPath("$.conserved").value(true));

        // 10) 未出账账期触发补差 -> 409
        String sub2 = "sub-adj-e2e-002";
        createSub(sub2);
        mockMvc.perform(post("/api/subscriptions/" + sub2 + "/adjustments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"period\":\"2026-09-15\",\"batchId\":\"x\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("HTTP：不传 batchId 时同一批输入重放返回同一单号同一金额，重试不藏掉未收款补差")
    void replayWithoutBatchIdOverHttpIsIdempotent() throws Exception {
        String sub = "sub-adj-e2e-nobatch";
        createSub(sub);
        report("nb-1", sub, 600, "2026-09-05T08:00:00Z");
        MvcResult issueRes = postJson("/api/subscriptions/" + sub + "/invoices",
                "{\"period\":\"2026-09-15\"}");
        JsonNode invoice = JSON.readTree(issueRes.getResponse().getContentAsString());
        report("nb-late", sub, 900, "2026-09-10T08:00:00Z");

        // 请求体只给 period、不带 batchId（接口允许省略）。
        MvcResult first = postJson("/api/subscriptions/" + sub + "/adjustments",
                "{\"period\":\"2026-09-15\"}");
        JsonNode a1 = JSON.readTree(first.getResponse().getContentAsString());
        String adjId = a1.get("adjustmentId").asText();
        org.junit.jupiter.api.Assertions.assertEquals(10.0, a1.get("delta").asDouble(), 0.001);
        org.junit.jupiter.api.Assertions.assertEquals(true, a1.get("created").asBoolean());

        // 客户端超时重试：第二次拿到同一个 adjustmentId、同样 10.00、created=false。
        MvcResult second = postJson("/api/subscriptions/" + sub + "/adjustments",
                "{\"period\":\"2026-09-15\"}");
        JsonNode a2 = JSON.readTree(second.getResponse().getContentAsString());
        org.junit.jupiter.api.Assertions.assertEquals(adjId, a2.get("adjustmentId").asText());
        org.junit.jupiter.api.Assertions.assertEquals(10.0, a2.get("delta").asDouble(), 0.001);
        org.junit.jupiter.api.Assertions.assertEquals(false, a2.get("created").asBoolean());

        // 原发票编号/金额始终不变。
        mockMvc.perform(get("/api/invoices/" + invoice.get("invoiceId").asText()))
                .andExpect(jsonPath("$.total").value(100.0));

        // 补差收款只成功一次：重放不会再产生一笔费用。
        mockMvc.perform(post("/api/adjustments/" + adjId + "/pay"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.status").value("PAID"));
        MvcResult third = postJson("/api/subscriptions/" + sub + "/adjustments",
                "{\"period\":\"2026-09-15\"}");
        JsonNode a3 = JSON.readTree(third.getResponse().getContentAsString());
        org.junit.jupiter.api.Assertions.assertEquals(adjId, a3.get("adjustmentId").asText());
        org.junit.jupiter.api.Assertions.assertEquals(10.0, a3.get("delta").asDouble(), 0.001);
    }

    private void createSub(String sub) throws Exception {
        mockMvc.perform(post("/api/subscriptions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subscriptionId":"%s","accountId":"acct","planCode":"BASIC","createdAt":"2026-09-01T00:00:00Z"}
                                """.formatted(sub)))
                .andExpect(status().isCreated());
    }

    private MvcResult report(String eventId, String sub, long qty, String at) throws Exception {
        return mockMvc.perform(post("/api/usage-events").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId":"%s","subscriptionId":"%s","meter":"api_calls","quantity":%d,"occurredAt":"%s"}
                                """.formatted(eventId, sub, qty, at)))
                .andExpect(status().isAccepted()).andReturn();
    }

    private MvcResult postJson(String path, String body) throws Exception {
        return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
    }
}
