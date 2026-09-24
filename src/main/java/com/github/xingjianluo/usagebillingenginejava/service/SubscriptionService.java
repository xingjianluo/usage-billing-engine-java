package com.github.xingjianluo.usagebillingenginejava.service;

import com.github.xingjianluo.usagebillingenginejava.domain.Plan;
import com.github.xingjianluo.usagebillingenginejava.domain.PlanChangeType;
import com.github.xingjianluo.usagebillingenginejava.domain.Subscription;
import com.github.xingjianluo.usagebillingenginejava.domain.SubscriptionVersion;
import com.github.xingjianluo.usagebillingenginejava.repo.PlanRepository;
import com.github.xingjianluo.usagebillingenginejava.repo.RepositoryLocks;
import com.github.xingjianluo.usagebillingenginejava.repo.SubscriptionRepository;
import com.github.xingjianluo.usagebillingenginejava.service.dto.PlanChangeResult;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 订阅服务。
 * 周期内变更只是追加一个“自 effectiveAt 起生效”的版本，不立即产生交易；
 * 出账时按各版本覆盖的时间片比例分摊基础费，因此不会重复扣费或漏计。
 * 同一订阅的变更通过订阅级锁串行化，避免乱序变更把版本链写乱。
 */
@Service
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    private final SubscriptionRepository subscriptionRepository;
    private final PlanRepository planRepository;
    private final RepositoryLocks locks;
    private final Clock clock;

    public SubscriptionService(SubscriptionRepository subscriptionRepository,
                                PlanRepository planRepository, RepositoryLocks locks, Clock clock) {
        this.subscriptionRepository = subscriptionRepository;
        this.planRepository = planRepository;
        this.locks = locks;
        this.clock = clock;
    }

    public Subscription createSubscription(String subscriptionId, String accountId,
                                           String initialPlanCode) {
        return createSubscription(subscriptionId, accountId, initialPlanCode, null);
    }

    /**
     * 创建订阅；createdAtIso 可空（取当前时钟）。显式创建时间用于回填历史订阅与确定性测试。
     */
    public Subscription createSubscription(String subscriptionId, String accountId,
                                           String initialPlanCode, String createdAtIso) {
        log.info("创建订阅输入: subscriptionId={}, accountId={}, planCode={}, createdAt={}",
                subscriptionId, accountId, initialPlanCode, createdAtIso);
        if (subscriptionId == null || subscriptionId.isBlank()
                || accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("subscriptionId 与 accountId 必填");
        }
        Plan plan = planRepository.findByCode(initialPlanCode)
                .orElseThrow(() -> new IllegalArgumentException("套餐不存在: " + initialPlanCode));
        Instant createdAt;
        try {
            createdAt = createdAtIso == null
                    ? Instant.now(clock) : Instant.parse(createdAtIso);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("createdAt 不是合法 ISO-8601 时间: " + createdAtIso);
        }

        ReentrantLock lock = locks.forKey("sub:" + subscriptionId);
        lock.lock();
        try {
            var existing = subscriptionRepository.findById(subscriptionId);
            if (existing.isPresent()) {
                log.info("订阅已存在，幂等返回既有订阅: subscriptionId={}", subscriptionId);
                return existing.get();
            }
            Subscription subscription = new Subscription(subscriptionId, accountId,
                    plan.code(), createdAt);
            subscriptionRepository.save(subscription);
            log.info("订阅已创建: subscriptionId={}, initialPlan={}, createdAt={}",
                    subscriptionId, plan.code(), createdAt);
            return subscription;
        } finally {
            lock.unlock();
        }
    }

    public PlanChangeResult changePlan(String subscriptionId, String newPlanCode,
                                       String effectiveAtIso) {
        log.info("套餐变更输入: subscriptionId={}, newPlan={}, effectiveAt={}",
                subscriptionId, newPlanCode, effectiveAtIso);
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new IllegalArgumentException("订阅不存在: " + subscriptionId));
        Plan newPlan = planRepository.findByCode(newPlanCode)
                .orElseThrow(() -> new IllegalArgumentException("套餐不存在: " + newPlanCode));

        Instant effectiveAt;
        try {
            effectiveAt = effectiveAtIso == null
                    ? Instant.now(clock) : Instant.parse(effectiveAtIso);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("effectiveAt 不是合法 ISO-8601 时间: " + effectiveAtIso);
        }

        ReentrantLock lock = locks.forKey("sub:" + subscriptionId);
        lock.lock();
        try {
            var versions = subscription.versions();
            Instant lastEffective = versions.get(versions.size() - 1).effectiveAt();
            String currentPlanCode = subscription.planAt(Instant.now(clock));
            if (newPlan.code().equals(currentPlanCode)) {
                log.info("目标套餐与当前套餐相同，幂等忽略: subscriptionId={}, plan={}",
                        subscriptionId, newPlan.code());
                return new PlanChangeResult(subscriptionId, currentPlanCode, newPlan.code(),
                        null, effectiveAt.toString());
            }
            // 允许回填当前计费周期内的变更点（支持补登记与确定性测试），
            // 但不得早于上一个版本点，以免版本链乱序。
            if (effectiveAt.isBefore(lastEffective)) {
                throw new IllegalArgumentException(
                        "生效时间不得早于最近一次变更: " + lastEffective + " > " + effectiveAt);
            }
            if (effectiveAt.isBefore(subscription.createdAt())) {
                throw new IllegalArgumentException(
                        "生效时间不得早于订阅创建时间: " + subscription.createdAt());
            }
            subscription.appendVersion(new SubscriptionVersion(newPlan.code(), effectiveAt));
            Plan oldPlan = planRepository.findByCode(currentPlanCode)
                    .orElseThrow(() -> new IllegalStateException("当前套餐缺失: " + currentPlanCode));
            PlanChangeType type = newPlan.basePrice().value().compareTo(oldPlan.basePrice().value()) >= 0
                    ? PlanChangeType.UPGRADE : PlanChangeType.DOWNGRADE;
            log.info("套餐变更已登记: subscriptionId={}, {}: {} -> {}, effectiveAt={}，出账时按时间片分摊",
                    subscriptionId, type, oldPlan.code(), newPlan.code(), effectiveAt);
            return new PlanChangeResult(subscriptionId, oldPlan.code(), newPlan.code(),
                    type, effectiveAt.toString());
        } finally {
            lock.unlock();
        }
    }
}
