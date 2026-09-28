package com.github.xingjianluo.usagebillingenginejava.tests;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.usagebillingenginejava.tests.config.FixedClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 结算后补差 HTTP 链路：开票支付 -> 迟到补报 -> 补差幂等 -> 账本守恒 -> 退款联动冲回。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockConfig.class)
class PostBillingAdjustmentApiTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void reset() {
        TestResetter.resetAll();
    }

    @Test
    @DisplayName("HTTP：已支付账期迟到补报触发补差，重放幂等，账本守恒，退款冲回，原账单不改写")
    void adjustmentLifecycleOverHttp() throws Exception {
        String sub = "sub-adj-api-001";

        mockMvc.perform(post("/api/subscriptions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subscriptionId":"%s","accountId":"acct","planCode":"BASIC","createdAt":"2026-09-01T00:00:00Z"}
                                """.formatted(sub)))
                .andExpect(status().isCreated());
        report("a-e1", sub, 1000, "2026-09-05T08:00:00Z");

        MvcResult issue = mockMvc.perform(post("/api/subscriptions/" + sub + "/invoices")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"period":"2026-09-15"}"""))
                .andExpect(status().isOk()).andReturn();
        String invoiceId = json.readTree(issue.getResponse().getContentAsString())
                .get("invoiceId").asText();
        mockMvc.perform(post("/api/invoices/" + invoiceId + "/pay"))
                .andExpect(jsonPath("$.success").value(true));

        // 出账后迟到补报 500 次（整期 1500 -> 用量费 10.00），补差即时收款
        report("a-e2-late", sub, 500, "2026-09-24T09:00:00Z");
        MvcResult adj1 = mockMvc.perform(post("/api/subscriptions/" + sub + "/adjustments")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"period":"2026-09-15"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.amount").value(10.00))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andReturn();
        String adjustmentId = json.readTree(adj1.getResponse().getContentAsString())
                .get("adjustmentId").asText();

        // 重放：同内容 -> created=false，同一张补差单
        mockMvc.perform(post("/api/subscriptions/" + sub + "/adjustments")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"period":"2026-09-15"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(false))
                .andExpect(jsonPath("$.adjustmentId").value(adjustmentId));

        // 列表只有一张补差
        mockMvc.perform(get("/api/subscriptions/" + sub + "/adjustments")
                        .param("period", "2026-09-15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].amount").value(10.00));

        // 原账单金额/编号不变
        mockMvc.perform(get("/api/invoices/" + invoiceId))
                .andExpect(jsonPath("$.total").value(100.00))
                .andExpect(jsonPath("$.status").value("PAID"));

        // 账本：CHARGE 110
        mockMvc.perform(get("/api/subscriptions/" + sub + "/ledger"))
                .andExpect(jsonPath("$.charge").value(110.00));

        // 再补一批 500（整期 2000 -> 用量费 20，净增 10）
        report("a-e3-late", sub, 500, "2026-09-24T10:00:00Z");
        mockMvc.perform(post("/api/subscriptions/" + sub + "/adjustments")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"period":"2026-09-15"}"""))
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.amount").value(10.00));

        // 退款联动冲回：REFUND = 100 + 10 + 10 = 120，守恒
        mockMvc.perform(post("/api/invoices/" + invoiceId + "/refund"))
                .andExpect(jsonPath("$.invoiceStatus").value("VOID"));
        mockMvc.perform(get("/api/subscriptions/" + sub + "/ledger"))
                .andExpect(jsonPath("$.conserved").value(true))
                .andExpect(jsonPath("$.refund").value(120.00));

        // 已冲回的补差显式收取：拒绝（VOID）
        mockMvc.perform(post("/api/adjustments/" + adjustmentId + "/collect"))
                .andExpect(jsonPath("$.status").value("VOID"));
    }

    private void report(String eventId, String sub, long qty, String at) throws Exception {
        mockMvc.perform(post("/api/usage-events").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId":"%s","subscriptionId":"%s","meter":"api_calls","quantity":%d,"occurredAt":"%s"}
                                """.formatted(eventId, sub, qty, at)))
                .andExpect(status().isAccepted());
    }
}
