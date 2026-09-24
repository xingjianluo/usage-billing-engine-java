package com.github.xingjianluo.usagebillingenginejava.web.dto;

public record SubscriptionRequest(String subscriptionId, String accountId, String planCode,
                                  String createdAt) {
}
