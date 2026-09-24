package com.github.xingjianluo.usagebillingenginejava.web.dto;

public record UsageEventRequest(String eventId, String subscriptionId, String meter,
                                Long quantity, String occurredAt) {
}
