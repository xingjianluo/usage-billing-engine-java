package com.github.xingjianluo.usagebillingenginejava.repo;

import com.github.xingjianluo.usagebillingenginejava.domain.UsageEvent;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/**
 * 用量事件内存仓储。
 * - eventId 全局唯一，insertIfAbsent 原子去重：重复上报绝不产生第二条计量；
 * - 周期窗口查询以事件发生时间 occurredAt 为准，与接收顺序无关，
 *   因此乱序（B 先于 A 到达）与迟到（下个周期才上报上周期事件）都能被正确归属周期；
 * - 返回结果按 occurredAt 升序，保证重放聚合顺序确定。
 */
@Repository
public class InMemoryUsageEventRepository implements UsageEventRepository {

    private final ConcurrentHashMap<String, UsageEvent> events = new ConcurrentHashMap<>();

    @Override
    public Optional<UsageEvent> findByEventId(String eventId) {
        return Optional.ofNullable(events.get(eventId));
    }

    @Override
    public boolean insertIfAbsent(UsageEvent event) {
        return events.putIfAbsent(event.eventId(), event) == null;
    }

    @Override
    public List<UsageEvent> findInWindow(String subscriptionId, Instant from, Instant to) {
        return events.values().stream()
                .filter(e -> e.subscriptionId().equals(subscriptionId))
                .filter(e -> !e.occurredAt().isBefore(from) && e.occurredAt().isBefore(to))
                .sorted(Comparator.comparing(UsageEvent::occurredAt)
                        .thenComparing(UsageEvent::eventId))
                .toList();
    }

    /** 仅供测试隔离使用：清空全部数据。 */
    public void clearAll() {
        events.clear();
    }
}
