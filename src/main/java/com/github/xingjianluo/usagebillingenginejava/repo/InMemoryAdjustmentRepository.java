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
 * - 幂等键 (subscriptionId, periodStart, batchId)：同一批迟到用量并发/重放只有一个 insert 成功，
 *   落败方回读同一张补差单，从根上杜绝“同一批输入重复生成补差/重复入账”；
 * - batchId 为 null（调用方未提供幂等键）时不参与幂等键去重，由服务层订阅锁串行化；
 * - 查询按 sequenceNo 升序，保证多批补差累计口径确定、可复现。
 */
@Repository
public class InMemoryAdjustmentRepository implements AdjustmentRepository {

    private final ConcurrentHashMap<String, Adjustment> byId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> batchKeyToId = new ConcurrentHashMap<>();

    private static String uniqueKey(String subscriptionId, BillingPeriod period, String batchId) {
        return subscriptionId + "|" + period.start() + "|" + batchId;
    }

    @Override
    public Optional<Adjustment> insertIfAbsent(Adjustment adjustment) {
        if (adjustment.batchId() == null) {
            byId.put(adjustment.id(), adjustment);
            return Optional.empty();
        }
        String key = uniqueKey(adjustment.subscriptionId(), adjustment.period(),
                adjustment.batchId());
        String existing = batchKeyToId.putIfAbsent(key, adjustment.id());
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
    public Optional<Adjustment> findByBatch(String subscriptionId, BillingPeriod period,
                                            String batchId) {
        if (batchId == null) {
            return Optional.empty();
        }
        String id = batchKeyToId.get(uniqueKey(subscriptionId, period, batchId));
        return Optional.ofNullable(id).map(byId::get);
    }

    @Override
    public List<Adjustment> findBySubscriptionAndPeriod(String subscriptionId,
                                                         BillingPeriod period) {
        return byId.values().stream()
                .filter(a -> a.subscriptionId().equals(subscriptionId))
                .filter(a -> a.period().start().equals(period.start()))
                .sorted(Comparator.comparingInt(Adjustment::sequenceNo)
                        .thenComparing(Adjustment::id))
                .toList();
    }

    @Override
    public void clearAll() {
        byId.clear();
        batchKeyToId.clear();
    }
}
