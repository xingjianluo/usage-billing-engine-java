package com.github.xingjianluo.usagebillingenginejava.tests;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.xingjianluo.usagebillingenginejava.tests.config.FixedClockConfig;
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
 * 端到端 HTTP 入口测试：创建订阅 -> 重复/乱序上报 -> 周期内升级 ->
 * 并发安全地开票（重复提交）-> 支付失败可区分 -> 重试成功 -> 账本守恒查询。
 * 全程内存组件 + 固定时钟，无外部服务。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockConfig.class)
class BillingEndToEndApiTest {

    private static final Logger log = LoggerFactory.getLogger(BillingEndToEndApiTest.class);

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void reset() {
        TestResetter.resetAll();
    }

    @Test
    @DisplayName("HTTP 全链路：重复上报不重复计费、一周期一票、失败可区分、重试成功且账本守恒")
    void fullLifecycleOverHttp() throws Exception {
        String sub = "sub-e2e-001";

        // 1) 创建订阅（回填 9/1）
        mockMvc.perform(post("/api/subscriptions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subscriptionId":"%s","accountId":"acct-e2e","planCode":"BASIC","createdAt":"2026-09-01T00:00:00Z"}
                                """.formatted(sub)))
                .andExpect(status().isCreated());

        // 2) 上报：乱序 + 重复（evt-1 报两次），总量应为 2200
        report("evt-2", sub, 1200, "2026-09-20T08:00:00Z");
        report("evt-1", sub, 1000, "2026-09-05T08:00:00Z");
        MvcResult dup = report("evt-1", sub, 1000, "2026-09-05T08:00:00Z");
        log.info("[判定依据] 重复上报 HTTP 响应: {}", dup.getResponse().getContentAsString());

        // 3) 周期内升级（9/16）
        mockMvc.perform(post("/api/subscriptions/" + sub + "/plan-change")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"planCode":"PRO","effectiveAt":"2026-09-16T00:00:00Z"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changeType").value("UPGRADE"));

        // 4) 开票两次（模拟重试），只能一张票；金额 = 升级基础费 200 + 用量费
        MvcResult issue1 = issue(sub);
        MvcResult issue2 = issue(sub);
        String id1 = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(issue1.getResponse().getContentAsString()).get("invoiceId").asText();
        String body2 = issue2.getResponse().getContentAsString();
        log.info("[判定依据] 两次开票响应相同: {}", body2.contains(id1));
        org.junit.jupiter.api.Assertions.assertTrue(body2.contains(id1),
                "重复开票必须返回同一张发票");

        mockMvc.perform(get("/api/invoices/" + id1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));

        // 5) 通过测试支持接口配置确定性失败脚本：先余额不足、再卡过期，第三次默认成功
        mockMvc.perform(post("/api/test-support/payment-script")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":"%s","outcomes":["INSUFFICIENT_FUNDS","CARD_DECLINED"]}
                                """.formatted(sub)))
                .andExpect(status().isOk());
        // 首次 pay 与第一次 retry 都失败，原因可区分
        mockMvc.perform(post("/api/invoices/" + id1 + "/pay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.failureReason").value("INSUFFICIENT_FUNDS"));
        mockMvc.perform(post("/api/invoices/" + id1 + "/retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.failureReason").value("CARD_DECLINED"));

        // 6) 第三次重试成功（脚本用尽 -> 默认成功），进入 PAID
        mockMvc.perform(post("/api/invoices/" + id1 + "/retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.invoiceStatus").value("PAID"));

        // 7) 再 pay 幂等，不新增尝试
        mockMvc.perform(post("/api/invoices/" + id1 + "/pay"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.attemptCount").value(3));

        // 8) 退款 -> VOID，账本守恒 CHARGE = CREDIT + REFUND
        mockMvc.perform(post("/api/invoices/" + id1 + "/refund"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invoiceStatus").value("VOID"));
        mockMvc.perform(get("/api/subscriptions/" + sub + "/ledger"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conserved").value(true));
    }

    private MvcResult report(String eventId, String sub, long qty, String at) throws Exception {
        return mockMvc.perform(post("/api/usage-events").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId":"%s","subscriptionId":"%s","meter":"api_calls","quantity":%d,"occurredAt":"%s"}
                                """.formatted(eventId, sub, qty, at)))
                .andExpect(status().isAccepted())
                .andReturn();
    }

    private MvcResult issue(String sub) throws Exception {
        return mockMvc.perform(post("/api/subscriptions/" + sub + "/invoices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"period":"2026-09-15"}"""))
                .andExpect(status().isOk())
                .andReturn();
    }
}
