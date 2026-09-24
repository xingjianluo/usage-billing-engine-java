package com.github.xingjianluo.usagebillingenginejava.repo;

import com.github.xingjianluo.usagebillingenginejava.domain.UsageEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface UsageEventRepository {

    Optional<UsageEvent> findByEventId(String eventId);

    /** 幂等插入：已存在同 eventId 返回 false。 */
    boolean insertIfAbsent(UsageEvent event);

    /** 取订阅在 [from, to) 时间窗内的事件（按 occurredAt 升序），窗口判定依据事件发生时间。 */
    List<UsageEvent> findInWindow(String subscriptionId, Instant from, Instant to);
}
