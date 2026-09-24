package com.github.xingjianluo.usagebillingenginejava.domain;

import java.time.Instant;

/**
 * 用量事件。
 * eventId 为上报方提供的幂等键（同一 eventId 重复上报只计一次）；
 * occurredAt 为事件真实发生时间（可乱序/迟到），receivedAt 为入库时间。
 */
public class UsageEvent {

    private final String eventId;
    private final String subscriptionId;
    private final String meter;
    private final long quantity;
    private final Instant occurredAt;
    private final Instant receivedAt;

    public UsageEvent(String eventId, String subscriptionId, String meter,
                      long quantity, Instant occurredAt, Instant receivedAt) {
        this.eventId = eventId;
        this.subscriptionId = subscriptionId;
        this.meter = meter;
        this.quantity = quantity;
        this.occurredAt = occurredAt;
        this.receivedAt = receivedAt;
    }

    public String eventId() {
        return eventId;
    }

    public String subscriptionId() {
        return subscriptionId;
    }

    public String meter() {
        return meter;
    }

    public long quantity() {
        return quantity;
    }

    public Instant occurredAt() {
        return occurredAt;
    }

    public Instant receivedAt() {
        return receivedAt;
    }
}
