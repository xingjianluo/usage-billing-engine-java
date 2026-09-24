package com.github.xingjianluo.usagebillingenginejava.repo;

import com.github.xingjianluo.usagebillingenginejava.domain.Subscription;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/**
 * 订阅内存仓储：save 幂等（同 id 重复保存不会覆盖，因为版本都追加在同一聚合实例上）。
 * 订阅级互斥由服务层通过 {@link RepositoryLocks} 保证。
 */
@Repository
public class InMemorySubscriptionRepository implements SubscriptionRepository {

    private final ConcurrentHashMap<String, Subscription> subscriptions = new ConcurrentHashMap<>();

    @Override
    public void save(Subscription subscription) {
        subscriptions.putIfAbsent(subscription.id(), subscription);
    }

    @Override
    public Optional<Subscription> findById(String id) {
        return Optional.ofNullable(subscriptions.get(id));
    }

    /** 仅供测试隔离使用：清空全部数据。 */
    public void clearAll() {
        subscriptions.clear();
    }
}
