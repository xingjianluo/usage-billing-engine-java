package com.github.xingjianluo.usagebillingenginejava.model;

import java.time.Instant;

/** 计费周期内某个套餐实际生效的时间片段。 */
public record PlanSegment(Instant from, Instant to, String planCode, long totalSeconds, long segmentSeconds) {
}
