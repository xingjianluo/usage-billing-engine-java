package com.github.xingjianluo.usagebillingenginejava.model;

import java.time.Instant;

/** 用量上报事件。eventId 为调用方提供的幂等键。 */
public record UsageEvent(
        String eventId,
        String accountId,
        String metric,
        long quantity,
        Instant occurredAt,
        Instant receivedAt) {
}
