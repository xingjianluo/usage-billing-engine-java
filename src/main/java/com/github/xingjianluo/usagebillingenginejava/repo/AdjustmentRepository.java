package com.github.xingjianluo.usagebillingenginejava.repo;

import com.github.xingjianluo.usagebillingenginejava.domain.Adjustment;
import com.github.xingjianluo.usagebillingenginejava.domain.BillingPeriod;
import java.util.List;
import java.util.Optional;

public interface AdjustmentRepository {

    /** 内容键唯一约束：同内容键已存在则返回既有补差。 */
    Optional<Adjustment> insertIfAbsent(Adjustment adjustment);

    Optional<Adjustment> findById(String id);

    Optional<Adjustment> findByContentKey(String subscriptionId, BillingPeriod period,
                                          String contentKey);

    /** 某账期全部补差，按生成序号升序。 */
    List<Adjustment> findBySubscriptionAndPeriod(String subscriptionId, BillingPeriod period);
}
