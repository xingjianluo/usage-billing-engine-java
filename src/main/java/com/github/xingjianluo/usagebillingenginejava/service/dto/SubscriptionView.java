package com.github.xingjianluo.usagebillingenginejava.service.dto;

import java.util.List;

/** 订阅视图（API 返回），仅暴露稳定字段。 */
public record SubscriptionView(String subscriptionId, String accountId, String createdAt,
                               List<VersionView> versions) {

    public record VersionView(String planCode, String effectiveAt) {
    }
}
