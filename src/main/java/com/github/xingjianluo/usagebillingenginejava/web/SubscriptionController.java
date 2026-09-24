package com.github.xingjianluo.usagebillingenginejava.web;

import com.github.xingjianluo.usagebillingenginejava.model.Subscription;
import com.github.xingjianluo.usagebillingenginejava.subscription.SubscriptionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/subscriptions")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    public SubscriptionController(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    public record CreateRequest(String accountId, Instant anchor, String plan) {}
    public record ChangeRequest(Instant effectiveAt, String plan) {}

    @PostMapping
    public Subscription create(@RequestBody CreateRequest request) {
        Instant anchor = request.anchor() != null ? request.anchor() : Instant.now();
        return subscriptionService.create(request.accountId(), anchor, request.plan());
    }

    @PostMapping("/{accountId}/changes")
    public Subscription change(@PathVariable String accountId, @RequestBody ChangeRequest request) {
        if (request.effectiveAt() == null || request.plan() == null) {
            throw new IllegalArgumentException("effectiveAt 与 plan 均不能为空");
        }
        return subscriptionService.changePlan(accountId, request.effectiveAt(), request.plan());
    }

    @GetMapping("/{accountId}")
    public Map<String, Object> get(@PathVariable String accountId) {
        Subscription s = subscriptionService.get(accountId);
        if (s == null) {
            throw new IllegalArgumentException("订阅不存在: " + accountId);
        }
        return Map.of(
                "accountId", s.accountId(),
                "anchor", s.anchor(),
                "planChanges", s.planChanges());
    }
}
