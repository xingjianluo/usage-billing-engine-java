package com.github.xingjianluo.usagebillingenginejava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.xingjianluo.usagebillingenginejava.domain.BillingPeriod;
import com.github.xingjianluo.usagebillingenginejava.repo.InvoiceRepository;
import com.github.xingjianluo.usagebillingenginejava.service.InvoiceService;
import com.github.xingjianluo.usagebillingenginejava.service.PeriodService;
import com.github.xingjianluo.usagebillingenginejava.service.SubscriptionService;
import com.github.xingjianluo.usagebillingenginejava.service.UsageIngestionService;
import com.github.xingjianluo.usagebillingenginejava.service.dto.IssueInvoiceResult;
import com.github.xingjianluo.usagebillingenginejava.tests.config.FixedClockConfig;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 并发开票：N 个线程同时为同一 (订阅, 周期) 开票，只能产生一张发票；
 * 串行重试开票同样只返回同一张。
 */
@SpringBootTest
@Import(FixedClockConfig.class)
class ConcurrentInvoiceTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentInvoiceTest.class);

    @Autowired private InvoiceService invoiceService;
    @Autowired private SubscriptionService subscriptionService;
    @Autowired private UsageIngestionService ingestionService;
    @Autowired private InvoiceRepository invoiceRepository;
    @Autowired private PeriodService periodService;

    private String subId;
    private BillingPeriod period;

    @BeforeEach
    void setUp() {
        TestResetter.resetAll();
        subId = "sub-conc-" + System.nanoTime();
        subscriptionService.createSubscription(subId, "acct-" + subId, "BASIC",
                "2026-09-01T00:00:00Z");
        period = periodService.periodOf(LocalDate.of(2026, 9, 15));
        // 20 条事件、每条约 100 次调用，落在 9/2~9/21，总量 2000。
        for (int i = 0; i < 20; i++) {
            int day = 2 + (i % 20);
            ingestionService.ingest("evt-" + i, subId, "api_calls", 100,
                    String.format("2026-09-%02dT08:00:00Z", day));
        }
    }

    @Test
    @DisplayName("20 线程并发开票：唯一发票，金额一致，仅一次 created=true")
    void concurrentIssueProducesSingleInvoice() throws Exception {
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<IssueInvoiceResult> results = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                results.add(invoiceService.issue(subId, period));
            });
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        Set<String> ids = results.stream().map(IssueInvoiceResult::invoiceId)
                .collect(Collectors.toSet());
        long createdCount = results.stream().filter(IssueInvoiceResult::created).count();
        Set<String> amounts = results.stream().map(r -> r.total().toString())
                .collect(Collectors.toSet());

        log.info("[关键输入] {} 个线程同时为 subscriptionId={} 周期 {} 开票", threads, subId,
                period.start());
        log.info("[判定依据] 结果数={}, 不同发票ID数={}, created=true 次数={}, 金额集合={}",
                results.size(), ids.size(), createdCount, amounts);

        assertEquals(1, ids.size(), "并发只能产生一张发票");
        assertEquals(1, createdCount, "只有一个线程真正创建，其余全部幂等返回");
        assertEquals(1, amounts.size(), "所有调用看到同一金额");
        assertEquals(1, invoiceRepository.findBySubscriptionAndPeriod(subId, period).stream().count());

        // 串行再调两次（模拟客户端重试）：仍是同一张
        List<String> retryIds = new ArrayList<>();
        retryIds.add(invoiceService.issue(subId, period).invoiceId());
        retryIds.add(invoiceService.issue(subId, period).invoiceId());
        assertTrue(retryIds.stream().allMatch(id -> id.equals(ids.iterator().next())),
                "重试不得新开票");
    }
}
