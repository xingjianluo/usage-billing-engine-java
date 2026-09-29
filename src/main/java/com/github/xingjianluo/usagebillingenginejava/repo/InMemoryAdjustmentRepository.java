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
 * - batchId 为 null（调用方未提供幂等键）时不参与批次键去重；改由内容指纹
 *   (subscriptionId, periodStart, contentFingerprint) 兜底：指纹由整期全部依据事件集合确定，
 *   同一批输入无论是否带 batchId、重放/并发/重试都只会对应同一笔补差单；
 * - 查询按 sequenceNo 升序，保证多批补差累计口径确定、可复现。
 */
@Repository
public class InMemoryAdjustmentRepository implements AdjustmentRepository {

    private final ConcurrentHashMap<String, Adjustment> byId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> batchKeyToId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> fingerprintKeyToId = new ConcurrentHashMap<>();

    private static String uniqueKey(String subscriptionId, BillingPeriod period, String batchId) {
        return subscriptionId + "|" + period.start() + "|" + batchId;
    }

    private static String fingerprintKey(String subscriptionId, BillingPeriod period,
                                         String contentFingerprint) {
        return subscriptionId + "|FP|" + period.start() + "|" + contentFingerprint;
    }

    @Override
    public Optional<Adjustment> insertIfAbsent(Adjustment adjustment) {
        // 内容指纹对带/不带 batchId 的补差单一视同仁地建立索引，保证无批次键也可幂等回读。
        String fpKey = fingerprintKey(adjustment.subscriptionId(), adjustment.period(),
                adjustment.contentFingerprint());
        String byFingerprint = fingerprintKeyToId.putIfAbsent(fpKey, adjustment.id());
        if (byFingerprint != null) {
            return Optional.of(byId.get(byFingerprint));
        }
        if (adjustment.batchId() == null) {
            byId.put(adjustment.id(), adjustment);
            return Optional.empty();
        }
        String key = uniqueKey(adjustment.subscriptionId(), adjustment.period(),
                adjustment.batchId());
        String existing = batchKeyToId.putIfAbsent(key, adjustment.id());
        if (existing != null) {
            // 指纹未落键（不同内容），但批次键撞车：回滚指纹键，回读既有批次单。
            fingerprintKeyToId.remove(fpKey, adjustment.id());
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
    public Optional<Adjustment> findByContentFingerprint(String subscriptionId, BillingPeriod period,
                                                         String contentFingerprint) {
        String id = fingerprintKeyToId.get(fingerprintKey(subscriptionId, period,
                contentFingerprint));
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
        fingerprintKeyToId.clear();
    }
}
