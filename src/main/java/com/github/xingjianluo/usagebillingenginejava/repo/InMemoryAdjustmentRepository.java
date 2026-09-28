package com.github.xingjianluo.usagebillingenginejava.repo;

import com.github.xingjianluo.usagebillingenginejava.domain.Adjustment;
import com.github.xingjianluo.usagebillingenginejava.domain.BillingPeriod;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/**
 * 补差单内存仓储。
 * 唯一性以 (subscriptionId, periodStart, contentKey) 为键：同一批迟到用量被并发或重放
 * 触发时只有一个 insert 成功，其余回读同一张补差单，从根上保证“同批输入一张补差单”。
 */
@Repository
public class InMemoryAdjustmentRepository implements AdjustmentRepository {

    private final ConcurrentHashMap<String, Adjustment> byId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> contentKeyToId = new ConcurrentHashMap<>();

    private static String contentUniqueKey(String subscriptionId, BillingPeriod period,
                                           String contentKey) {
        return subscriptionId + "|" + period.start() + "|" + contentKey;
    }

    @Override
    public Optional<Adjustment> insertIfAbsent(Adjustment adjustment) {
        String key = contentUniqueKey(adjustment.subscriptionId(), adjustment.period(),
                adjustment.contentKey());
        String existing = contentKeyToId.putIfAbsent(key, adjustment.id());
        if (existing != null) {
            return Optional.of(byId.get(existing));
        }
        byId.put(adjustment.id(), adjustment);
        return Optional.empty();
    }

    @Override
    public Optional<Adjustment> findById(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<Adjustment> findByContentKey(String subscriptionId, BillingPeriod period,
                                                 String contentKey) {
        String id = contentKeyToId.get(contentUniqueKey(subscriptionId, period, contentKey));
        return Optional.ofNullable(id).map(byId::get);
    }

    @Override
    public List<Adjustment> findBySubscriptionAndPeriod(String subscriptionId,
                                                        BillingPeriod period) {
        return byId.values().stream()
                .filter(a -> a.subscriptionId().equals(subscriptionId))
                .filter(a -> a.period().equals(period))
                .sorted(Comparator.comparingInt(Adjustment::sequence)
                        .thenComparing(Adjustment::id))
                .toList();
    }

    /** 仅供测试隔离使用：清空全部数据。 */
    public void clearAll() {
        byId.clear();
        contentKeyToId.clear();
    }
}
