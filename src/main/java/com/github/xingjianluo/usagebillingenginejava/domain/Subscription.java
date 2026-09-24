package com.github.xingjianluo.usagebillingenginejava.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 订阅聚合。版本列表按 effectiveAt 升序保存，变更（升降级）即追加新版本。
 */
public class Subscription {

    private final String id;
    private final String accountId;
    private final Instant createdAt;
    private final List<SubscriptionVersion> versions = new ArrayList<>();

    public Subscription(String id, String accountId, String initialPlanCode, Instant createdAt) {
        this.id = id;
        this.accountId = accountId;
        this.createdAt = createdAt;
        this.versions.add(new SubscriptionVersion(initialPlanCode, createdAt));
    }

    public String id() {
        return id;
    }

    public String accountId() {
        return accountId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public List<SubscriptionVersion> versions() {
        return Collections.unmodifiableList(versions);
    }

    public String planAt(Instant point) {
        String code = versions.get(0).planCode();
        for (SubscriptionVersion v : versions) {
            if (!point.isBefore(v.effectiveAt())) {
                code = v.planCode();
            }
        }
        return code;
    }

    /**
     * 追加版本（升/降级）。调用方（SubscriptionService）须保证 effectiveAt 不早于
     * 最后一个版本，且通过仓储的订阅级锁串行化。
     */
    public void appendVersion(SubscriptionVersion version) {
        versions.add(version);
    }
}
