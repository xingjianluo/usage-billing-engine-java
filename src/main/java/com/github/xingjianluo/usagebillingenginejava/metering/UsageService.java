package com.github.xingjianluo.usagebillingenginejava.metering;

import com.github.xingjianluo.usagebillingenginejava.model.IngestResult;
import com.github.xingjianluo.usagebillingenginejava.model.UsageEvent;
import com.github.xingjianluo.usagebillingenginejava.store.InMemoryStore;
import com.github.xingjianluo.usagebillingenginejava.subscription.SubscriptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用量采集入口。
 *
 * 幂等：以调用方提供的 eventId 为唯一键；底层 putIfAbsent 原子判定，
 * 任何重复上报（网络重试、消息重放、并发双发）只计量一次。
 *
 * 乱序/迟到：计量归属按 occurredAt（业务发生时间）而非接收时间；
 * 因此先到/后到、早发生/晚发生的事件在同一窗口内聚合结果完全一致。
 *
 * 窗口：拒绝 occurredAt 落在未来或早于当前结算窗口起点的事件，
 * 返回 REJECTED；事件仍保留（getEvent 可查），但不计入聚合，
 * 使“迟到超过一个周期”的输入有明确、可解释的结论而不是被静默吞掉。
 */
@Service
public class UsageService {

    private static final Logger log = LoggerFactory.getLogger(UsageService.class);

    /** 事件可被接受的回溯窗口：当前周期起点之前的事件视为过期迟到。 */
    public static final long ACCEPT_LOOKBACK_SECONDS = SubscriptionService.PERIOD_SECONDS;

    private final InMemoryStore store;

    public UsageService(InMemoryStore store) {
        this.store = store;
    }

    public IngestResult ingest(UsageEvent input) {
        validate(input);
        Instant now = Instant.now();
        UsageEvent event = new UsageEvent(
                input.eventId(), input.accountId(), input.metric(),
                input.quantity(), input.occurredAt(),
                input.receivedAt() != null ? input.receivedAt() : now);

        // 同一账号的检查-写入串行化；eventId 全局 putIfAbsent 兜底去重。
        store.lockFor(event.accountId()).lock();
        try {
            UsageEvent existing = store.getEvent(event.eventId());
            if (existing != null) {
                log.info("用量重复上报 eventId={} account={} metric={} qty={} -> DUPLICATE（命中既有事件 {}）",
                        event.eventId(), event.accountId(), event.metric(), event.quantity(),
                        existing.receivedAt());
                return IngestResult.DUPLICATE;
            }

            Instant windowStart = now.minusSeconds(ACCEPT_LOOKBACK_SECONDS);
            if (event.occurredAt().isAfter(now)) {
                store.putEventIfAbsent(event);
                store.markRejected(event.eventId());
                log.warn("用量上报被拒绝 eventId={} account={} occurredAt={} 晚于当前 {} -> REJECTED(未来时间)",
                        event.eventId(), event.accountId(), event.occurredAt(), now);
                return IngestResult.REJECTED;
            }
            if (event.occurredAt().isBefore(windowStart)) {
                store.putEventIfAbsent(event);
                store.markRejected(event.eventId());
                log.warn("用量上报被拒绝 eventId={} account={} occurredAt={} 早于窗口起点 {} -> REJECTED(迟到超窗)",
                        event.eventId(), event.accountId(), event.occurredAt(), windowStart);
                return IngestResult.REJECTED;
            }

            boolean inserted = store.putEventIfAbsent(event);
            if (!inserted) {
                log.info("用量重复上报 eventId={} -> DUPLICATE（并发竞争落败）", event.eventId());
                return IngestResult.DUPLICATE;
            }
            log.info("用量接受 eventId={} account={} metric={} qty={} occurredAt={} receivedAt={} -> ACCEPTED",
                    event.eventId(), event.accountId(), event.metric(), event.quantity(),
                    event.occurredAt(), event.receivedAt());
            return IngestResult.ACCEPTED;
        } finally {
            store.lockFor(event.accountId()).unlock();
        }
    }

    private void validate(UsageEvent event) {
        if (event == null || event.eventId() == null || event.eventId().isBlank()) {
            throw new IllegalArgumentException("eventId 不能为空");
        }
        if (event.accountId() == null || event.accountId().isBlank()) {
            throw new IllegalArgumentException("accountId 不能为空");
        }
        if (event.metric() == null || event.metric().isBlank()) {
            throw new IllegalArgumentException("metric 不能为空");
        }
        if (event.quantity() <= 0) {
            throw new IllegalArgumentException("quantity 必须为正数: " + event.quantity());
        }
        if (event.occurredAt() == null) {
            throw new IllegalArgumentException("occurredAt 不能为空");
        }
    }

    /**
     * 聚合 [from, to) 窗口内去重后的用量，按 metric 求和。
     * 窗口判定只依赖 occurredAt，与接收顺序无关，因此对乱序/迟到（窗口内）重放结果稳定。
     */
    public Map<String, Long> aggregate(String accountId, Instant from, Instant to) {
        Map<String, Long> result = new LinkedHashMap<>();
        List<UsageEvent> events = store.eventsByAccount(accountId);
        for (UsageEvent e : events) {
            if (store.isRejected(e.eventId())) {
                continue; // REJECTED 事件保留可审计，但永不计量
            }
            boolean inWindow = !e.occurredAt().isBefore(from) && e.occurredAt().isBefore(to);
            if (!inWindow) {
                continue;
            }
            result.merge(e.metric(), e.quantity(), Long::sum);
        }
        log.info("用量聚合 account={} window=[{},{}) events={} totals={}",
                accountId, from, to, events.size(), result);
        return result;
    }

    /**
     * 分段聚合：仅统计发生在 [from, to) 内的事件（用于跨套餐变更点分段计费）。
     */
    public Map<String, Long> aggregateBetween(String accountId, Instant from, Instant to) {
        return aggregate(accountId, from, to);
    }

    public List<UsageEvent> listEvents(String accountId) {
        return store.eventsByAccount(accountId).stream()
                .sorted(java.util.Comparator.comparing(UsageEvent::occurredAt))
                .toList();
    }
}
