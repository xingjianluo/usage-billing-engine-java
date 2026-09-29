package com.github.xingjianluo.usagebillingenginejava.repo;

import com.github.xingjianluo.usagebillingenginejava.domain.Adjustment;
import com.github.xingjianluo.usagebillingenginejava.domain.BillingPeriod;
import java.util.List;
import java.util.Optional;

/** 补差单仓储。 */
public interface AdjustmentRepository {

    /** 幂等插入：返回已存在的同键补差单（并发落败方回读）。 */
    Optional<Adjustment> insertIfAbsent(Adjustment adjustment);

    Optional<Adjustment> findById(String id);

    /** 同一 (订阅, 账期, 批次键) 的补差单（幂等键）。 */
    Optional<Adjustment> findByBatch(String subscriptionId, BillingPeriod period, String batchId);

    /**
     * 同一 (订阅, 账期, 内容指纹) 的补差单。内容指纹由“整期全部依据事件集合”确定，
     * 使调用方不提供 batchId 时，同一批输入的重放/并发/重试仍能回读同一笔补差。
     */
    Optional<Adjustment> findByContentFingerprint(String subscriptionId, BillingPeriod period,
                                                  String contentFingerprint);

    /** 同一 (订阅, 账期) 的全部补差单，按序号升序。 */
    List<Adjustment> findBySubscriptionAndPeriod(String subscriptionId, BillingPeriod period);

    /** 仅供测试隔离使用：清空全部数据。 */
    void clearAll();
}
