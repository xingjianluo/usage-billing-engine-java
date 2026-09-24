package com.github.xingjianluo.usagebillingenginejava.repo;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

/**
 * 按键互斥锁：为同一订阅/发票的复合操作提供串行化，
 * 保证“周期内变更 + 开票”“并发开票”“支付与重试”等交叉操作的一致性。
 * 单机内存实现；分布式部署应替换为数据库行锁/分布式锁。
 */
@Component
public class RepositoryLocks {

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public ReentrantLock forKey(String key) {
        return locks.computeIfAbsent(key, k -> new ReentrantLock());
    }
}
