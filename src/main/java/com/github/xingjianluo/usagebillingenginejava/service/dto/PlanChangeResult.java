package com.github.xingjianluo.usagebillingenginejava.service.dto;

import com.github.xingjianluo.usagebillingenginejava.domain.PlanChangeType;

public record PlanChangeResult(String subscriptionId, String fromPlan, String toPlan,
                               PlanChangeType changeType, String effectiveAt) {
}
