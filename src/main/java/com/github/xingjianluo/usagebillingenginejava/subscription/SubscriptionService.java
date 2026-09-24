package com.github.xingjianluo.usagebillingenginejava.subscription;

import com.github.xingjianluo.usagebillingenginejava.model.PlanSegment;
import com.github.xingjianluo.usagebillingenginejava.model.Subscription;
import com.github.xingjianluo.usagebillingenginejava.pricing.PlanCatalog;
import com.github.xingjianluo.usagebillingenginejava.store.InMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * 订阅与周期内变更。
 *
 * 周期模型：以创建时 anchor 为起点，每 {@value #PERIOD_SECONDS} 秒为一个自然周期；
 * 任何时刻都能确定性地反推出所属周期 [start, end)，账单与发票均以周期起点为键。
 *
 * 变更模型：planChanges 是“生效时间点 -> 套餐编码”的有序映射（追加式、不覆盖历史）。
 * - 同一时刻重复变更：幂等，第二次起返回既有状态，不产生第二条变更；
 * - 更早时刻的补变更同样追加，切片段时自动按时间归位（支持乱序录入）；
 * - 账户创建自带 effectiveAt=anchor 的初始套餐条目。
 */
@Service
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    public static final long PERIOD_SECONDS = 30L * 24 * 60 * 60;

    private final InMemoryStore store;
    private final PlanCatalog catalog;

    public SubscriptionService(InMemoryStore store, PlanCatalog catalog) {
        this.store = store;
        this.catalog = catalog;
    }

    public Subscription create(String accountId, Instant anchor, String initialPlan) {
        catalog.require(initialPlan); // 快速失败：未知套餐
        store.lockFor(accountId).lock();
        try {
            Subscription existing = store.getSubscription(accountId);
            if (existing != null) {
                log.info("订阅已存在 account={} anchor={} -> 幂等返回既有订阅", accountId, existing.anchor());
                return existing;
            }
            TreeMap<Instant, String> changes = new TreeMap<>();
            changes.put(anchor, initialPlan);
            Subscription subscription = new Subscription(accountId, anchor, changes);
            store.saveSubscription(subscription);
            log.info("创建订阅 account={} anchor={} plan={}", accountId, anchor, initialPlan);
            return subscription;
        } finally {
            store.lockFor(accountId).unlock();
        }
    }

    public Subscription changePlan(String accountId, Instant effectiveAt, String newPlan) {
        catalog.require(newPlan);
        store.lockFor(accountId).lock();
        try {
            Subscription subscription = requireSubscription(accountId);
            if (effectiveAt.isBefore(subscription.anchor())) {
                throw new IllegalArgumentException("变更生效时间不得早于订阅创建时间");
            }
            SortedMap<Instant, String> changes = subscription.planChanges();
            String current = changes.get(effectiveAt);
            if (newPlan.equals(current)) {
                log.info("套餐变更幂等命中 account={} at={} plan={} -> 忽略重复请求",
                        accountId, effectiveAt, newPlan);
                return subscription;
            }
            // 仅在套餐确实不同时写入；同点换套餐属于更正，保留最新值（切分仍只产生一段）。
            String previousAtPoint = current;
            changes.put(effectiveAt, newPlan);
            store.saveSubscription(new Subscription(accountId, subscription.anchor(),
                    new TreeMap<>(changes)));
            log.info("套餐变更生效 account={} at={} {} -> {}（同点旧值={}）",
                    accountId, effectiveAt, previousAtPoint, newPlan, previousAtPoint);
            return store.getSubscription(accountId);
        } finally {
            store.lockFor(accountId).unlock();
        }
    }

    public Subscription get(String accountId) {
        return store.getSubscription(accountId);
    }

    private Subscription requireSubscription(String accountId) {
        Subscription s = store.getSubscription(accountId);
        if (s == null) {
            throw new IllegalStateException("订阅不存在: " + accountId);
        }
        return s;
    }

    /** 计算 at 所在周期的 [start, end)。start = anchor + k*PERIOD, k 为非负整数。 */
    public Instant[] periodBounds(Instant anchor, Instant at) {
        long offset = Duration.between(anchor, at).getSeconds();
        if (offset < 0) {
            offset = 0;
        }
        long index = offset / PERIOD_SECONDS;
        Instant start = anchor.plusSeconds(index * PERIOD_SECONDS);
        return new Instant[]{start, start.plusSeconds(PERIOD_SECONDS)};
    }

    /**
     * 切分周期内套餐生效片段。
     * 取 planChanges 落在 [periodStart, periodEnd) 内的条目作为边界，
     * 边界前沿用 floorEntry(periodStart) 的套餐。
     */
    public List<PlanSegment> segments(Subscription subscription, Instant periodStart, Instant periodEnd) {
        long total = Duration.between(periodStart, periodEnd).getSeconds();
        TreeMap<Instant, String> all = new TreeMap<>(subscription.planChanges());

        // 周期开始时生效的套餐（anchor 或更早一次变更）
        Map.Entry<Instant, String> floor = all.floorEntry(periodStart);
        String initialPlan = floor != null ? floor.getValue() : all.firstEntry().getValue();

        // 周期内的变更边界点
        SortedMap<Instant, String> within =
                all.subMap(periodStart, false, periodEnd, false);

        List<PlanSegment> segments = new ArrayList<>();
        Instant cursor = periodStart;
        String planCode = initialPlan;
        for (Map.Entry<Instant, String> e : within.entrySet()) {
            if (e.getKey().isAfter(cursor)) {
                segments.add(buildSegment(cursor, e.getKey(), planCode, total));
            }
            cursor = e.getKey();
            planCode = e.getValue();
        }
        segments.add(buildSegment(cursor, periodEnd, planCode, total));
        log.info("周期片段切分 account={} [{},{}) 变更点数={} -> {}",
                subscription.accountId(), periodStart, periodEnd, within.size(),
                segments.stream().map(s -> s.planCode() + ":" + s.segmentSeconds() + "s").toList());
        return segments;
    }

    private PlanSegment buildSegment(Instant from, Instant to, String planCode, long totalSeconds) {
        long seconds = Duration.between(from, to).getSeconds();
        return new PlanSegment(from, to, planCode, totalSeconds, seconds);
    }
}
