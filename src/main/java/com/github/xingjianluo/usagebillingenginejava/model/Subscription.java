package com.github.xingjianluo.usagebillingenginejava.model;

import java.time.Instant;
import java.util.SortedMap;

/**
 * 订阅。planChanges 的 key 为生效时间点，value 为当时生效的套餐编码；
 * 按时间有序，支持周期内多次升降级。
 */
public record Subscription(
        String accountId,
        Instant anchor,
        SortedMap<Instant, String> planChanges) {
}
