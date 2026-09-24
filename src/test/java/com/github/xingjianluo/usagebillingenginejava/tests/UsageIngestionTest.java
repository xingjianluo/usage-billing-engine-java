package com.github.xingjianluo.usagebillingenginejava.tests;

import com.github.xingjianluo.usagebillingenginejava.metering.UsageService;
import com.github.xingjianluo.usagebillingenginejava.model.IngestResult;
import com.github.xingjianluo.usagebillingenginejava.model.UsageEvent;
import com.github.xingjianluo.usagebillingenginejava.store.InMemoryStore;
import com.github.xingjianluo.usagebillingenginejava.subscription.SubscriptionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用量采集幂等性测试：重复上报、乱序到达、窗口内迟到均不得重复计量；
 * 超过窗口的迟到事件得到 REJECTED 而非静默吞掉。
 */
class UsageIngestionTest {

    private static final Logger log = LoggerFactory.getLogger(UsageIngestionTest.class);

    private final InMemoryStore store = new InMemoryStore();
    private final UsageService usageService = new UsageService(store);

    private UsageEvent event(String id, String account, String metric, long qty, Instant at) {
        return new UsageEvent(id, account, metric, qty, at, Instant.now());
    }

    @Test
    @DisplayName("重复上报（含并发重放）只计量一次")
    void duplicateReportedOnce() throws Exception {
        Instant base = Instant.now().minusSeconds(60);
        UsageEvent e = event("evt-1", "acct-dup", "api_calls", 100, base);

        IngestResult first = usageService.ingest(e);
        // 相同 eventId 串行重试 3 次
        IngestResult r2 = usageService.ingest(e);
        IngestResult r3 = usageService.ingest(e);

        // 并发双发：两个线程使用完全相同的事件
        final IngestResult[] race = new IngestResult[2];
        Thread t1 = new Thread(() -> race[0] = usageService.ingest(e));
        Thread t2 = new Thread(() -> race[1] = usageService.ingest(e));
        t1.start(); t2.start(); t1.join(); t2.join();

        Map<String, Long> agg = usageService.aggregate("acct-dup",
                base.minusSeconds(10), base.plusSeconds(10));

        log.info("[关键输入] 同一 evt-1 串行3次+并发2次 qty=100 [判定依据] eventId 唯一约束，结果 first={} r2={} race={}/{}",
                first, r2, race[0], race[1]);
        assertEquals(IngestResult.ACCEPTED, first);
        assertEquals(IngestResult.DUPLICATE, r2);
        assertEquals(IngestResult.DUPLICATE, r3);
        assertTrue(race[0] == IngestResult.DUPLICATE || race[1] == IngestResult.DUPLICATE);
        assertEquals(1L, store.eventsByAccount("acct-dup").size(), "事件只落盘一条");
        assertEquals(100L, agg.get("api_calls"), "聚合用量只能是 100");
    }

    @Test
    @DisplayName("乱序与窗口内迟到：按 occurredAt 聚合，结果与到达顺序无关")
    void outOfOrderAndLateWithinWindow() {
        Instant t1 = Instant.now().minusSeconds(300);
        Instant t2 = Instant.now().minusSeconds(200);
        Instant t3 = Instant.now().minusSeconds(100);

        // 故意按 t3, t1, t2 的乱序（含相对较晚到达）发送
        usageService.ingest(event("evt-c", "acct-ord", "api_calls", 30, t3));
        usageService.ingest(event("evt-a", "acct-ord", "api_calls", 10, t1));
        usageService.ingest(event("evt-b", "acct-ord", "api_calls", 20, t2));

        Map<String, Long> agg = usageService.aggregate("acct-ord",
                t1.minusSeconds(1), t3.plusSeconds(1));
        log.info("[关键输入] 乱序到达 occurredAt 间隔100s 数量 30/10/20 [判定依据] 按 occurredAt 归属窗口，聚合={}",
                agg);
        assertEquals(60L, agg.get("api_calls"));

        // 窗口内迟到重放：再补一条更早的窗口内事件，总量只增其一次
        IngestResult late = usageService.ingest(
                event("evt-late", "acct-ord", "api_calls", 5, t2.minusSeconds(50)));
        Map<String, Long> agg2 = usageService.aggregate("acct-ord",
                t1.minusSeconds(60), t3.plusSeconds(1));
        log.info("[关键输入] 迟到事件 evt-late qty=5 [判定依据] 在当前窗口内 -> {}, 新聚合={}",
                late, agg2);
        assertEquals(IngestResult.ACCEPTED, late);
        assertEquals(65L, agg2.get("api_calls"));
    }

    @Test
    @DisplayName("超过一个周期的迟到事件被 REJECTED，结论可解释且不计量")
    void lateBeyondWindowRejected() {
        Instant tooOld = Instant.now()
                .minusSeconds(SubscriptionService.PERIOD_SECONDS + 3600);
        IngestResult result = usageService.ingest(
                event("evt-old", "acct-old", "api_calls", 1000, tooOld));

        Map<String, Long> agg = usageService.aggregate("acct-old",
                tooOld.minusSeconds(1), Instant.now());
        log.info("[关键输入] occurredAt 早于当前窗口起点超过一个周期 [判定依据] 超过回溯窗口 -> {}, 聚合={}",
                result, agg);
        assertEquals(IngestResult.REJECTED, result);
        assertEquals(null, agg.get("api_calls"));
    }
}
