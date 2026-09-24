package com.github.xingjianluo.usagebillingenginejava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.xingjianluo.usagebillingenginejava.domain.BillingPeriod;
import com.github.xingjianluo.usagebillingenginejava.domain.IngestStatus;
import com.github.xingjianluo.usagebillingenginejava.domain.Invoice;
import com.github.xingjianluo.usagebillingenginejava.domain.Money;
import com.github.xingjianluo.usagebillingenginejava.repo.UsageEventRepository;
import com.github.xingjianluo.usagebillingenginejava.service.InvoiceService;
import com.github.xingjianluo.usagebillingenginejava.service.PeriodService;
import com.github.xingjianluo.usagebillingenginejava.service.SubscriptionService;
import com.github.xingjianluo.usagebillingenginejava.service.UsageIngestionService;
import com.github.xingjianluo.usagebillingenginejava.service.dto.IngestResult;
import com.github.xingjianluo.usagebillingenginejava.service.dto.IssueInvoiceResult;
import com.github.xingjianluo.usagebillingenginejava.tests.config.FixedClockConfig;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 用量采集与周期内聚合：重复、乱序、迟到上报的幂等性与账单可复现性。
 * 全部使用本地内存仓储与固定 Clock，无外部服务。
 */
@SpringBootTest
@org.springframework.context.annotation.Import(FixedClockConfig.class)
class UsageIngestionTest {

    private static final Logger log = LoggerFactory.getLogger(UsageIngestionTest.class);

    @Autowired
    private UsageIngestionService ingestionService;
    @Autowired
    private SubscriptionService subscriptionService;
    @Autowired
    private InvoiceService invoiceService;
    @Autowired
    private UsageEventRepository eventRepository;
    @Autowired
    private PeriodService periodService;

    private String subId;
    private BillingPeriod period;

    @BeforeEach
    void setUp() {
        TestResetter.resetAll();
        subId = "sub-usage-" + System.nanoTime();
        subscriptionService.createSubscription(subId, "acct-" + subId, "BASIC",
                "2026-09-01T00:00:00Z");
        period = periodService.periodOf(java.time.LocalDate.of(2026, 9, 15));
    }

    @Test
    @DisplayName("重复上报：同 eventId 多次提交，只有首次 ACCEPTED，其余 DUPLICATED 且结论一致")
    void duplicateEventsAreMeteredOnce() {
        String payloadTime = "2026-09-10T08:00:00Z";
        IngestResult first = ingestionService.ingest("evt-dup-1", subId, "api_calls", 500, payloadTime);
        IngestResult second = ingestionService.ingest("evt-dup-1", subId, "api_calls", 500, payloadTime);
        IngestResult third = ingestionService.ingest("evt-dup-1", subId, "api_calls", 500, payloadTime);

        log.info("[关键输入] eventId=evt-dup-1 qty=500 连续上报3次");
        log.info("[判定依据] first={}, second={}, third={}", first.status(), second.status(), third.status());

        assertEquals(IngestStatus.ACCEPTED, first.status());
        assertEquals(IngestStatus.DUPLICATE, second.status());
        assertEquals(IngestStatus.DUPLICATE, third.status());
        assertTrue(second.replaySafe() && third.replaySafe());

        List<?> stored = eventRepository.findInWindow(subId,
                periodService.startInstant(period), periodService.endInstant(period));
        assertEquals(1, stored.size(), "仓储中只能有一条事件，重复计量被杜绝");
    }

    @Test
    @DisplayName("内容冲突的同 eventId 上报被拒绝（区分于普通重复）")
    void conflictingDuplicateIsRejected() {
        ingestionService.ingest("evt-conflict", subId, "api_calls", 100, "2026-09-11T08:00:00Z");
        IngestResult conflict = ingestionService.ingest("evt-conflict", subId, "api_calls", 999,
                "2026-09-11T08:00:00Z");
        log.info("[关键输入] 同 eventId 不同数量 100 vs 999 -> {}", conflict.status());
        assertEquals(IngestStatus.DUPLICATE, conflict.status());
        assertTrue(conflict.reason().contains("内容不同"));
    }

    @Test
    @DisplayName("乱序与迟到上报：按 occurredAt 归属与排序，账单金额可复现")
    void outOfOrderAndLateEventsProduceReproducibleBill() {
        // 乱序：先到 9/20，再到 9/5；迟到：开票前一刻才补报 9/2 的事件。
        ingestionService.ingest("evt-late", subId, "api_calls", 600, "2026-09-02T06:00:00Z");
        ingestionService.ingest("evt-b", subId, "api_calls", 600, "2026-09-20T10:00:00Z");
        ingestionService.ingest("evt-a", subId, "api_calls", 600, "2026-09-05T10:00:00Z");
        // 乱序重复一条：绝不能叠加
        ingestionService.ingest("evt-b", subId, "api_calls", 600, "2026-09-20T10:00:00Z");

        IssueInvoiceResult r1 = invoiceService.issue(subId, period);
        Invoice invoice1 = invoiceService.getInvoice(r1.invoiceId());

        // 再次开票（重放）：必须返回同一张发票、同一金额
        IssueInvoiceResult r2 = invoiceService.issue(subId, period);
        Invoice invoice2 = invoiceService.getInvoice(r2.invoiceId());

        log.info("[关键输入] 3条事件(其中1条乱序+1条迟到)+1条重复, 总量={}", 600 * 3);
        log.info("[判定依据] 首次开票 id={} total={} 行={}; 重放开票 id={} total={}",
                invoice1.id(), invoice1.total(), invoice1.lines().size(),
                invoice2.id(), invoice2.total());

        assertEquals(invoice1.id(), invoice2.id(), "重放不得产生第二张发票");
        // 总量 1800：BASIC 前 1000 免费，超出 800 * 0.02 = 16.00；基础费 100.00；合计 116.00
        Money expectedUsage = Money.of("16.00");
        Money usageLine = invoice1.lines().stream()
                .filter(l -> l.type().equals("USAGE_TIER"))
                .map(l -> l.amount())
                .reduce(Money.zero(), Money::plus);
        assertEquals(expectedUsage, usageLine, "乱序/迟到不影响阶梯用量费");
        assertEquals(Money.of("116.00"), invoice1.total(), "账单金额可复现");
        assertFalse(r2.created(), "重复开票必须标记 created=false");
    }

    @Test
    @DisplayName("非法输入与未知订阅被 REJECTED，原因可解释")
    void invalidInputIsRejectedWithReason() {
        assertEquals(IngestStatus.REJECTED,
                ingestionService.ingest("evt-x", subId, "api_calls", -1,
                        "2026-09-05T10:00:00Z").status());
        assertEquals(IngestStatus.REJECTED,
                ingestionService.ingest("evt-y", "sub-does-not-exist", "api_calls", 1,
                        "2026-09-05T10:00:00Z").status());
        IngestResult badTime = ingestionService.ingest("evt-z", subId, "api_calls", 1,
                "not-a-time");
        assertEquals(IngestStatus.REJECTED, badTime.status());
        log.info("[判定依据] 非法数量/未知订阅/非法时间全部 REJECTED，示例原因: {}", badTime.reason());
    }
}
