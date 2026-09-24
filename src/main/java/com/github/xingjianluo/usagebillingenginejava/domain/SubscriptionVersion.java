package com.github.xingjianluo.usagebillingenginejava.domain;

import java.time.Instant;

/**
 * 订阅版本：effectiveAt 起订阅适用 planCode（含变更点当天，左闭右开）。
 */
public record SubscriptionVersion(String planCode, Instant effectiveAt) {
}
