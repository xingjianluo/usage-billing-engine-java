package com.github.xingjianluo.usagebillingenginejava.service;

import com.github.xingjianluo.usagebillingenginejava.domain.IngestStatus;
import com.github.xingjianluo.usagebillingenginejava.domain.UsageEvent;
import com.github.xingjianluo.usagebillingenginejava.repo.SubscriptionRepository;
import com.github.xingjianluo.usagebillingenginejava.repo.UsageEventRepository;
import com.github.xingjianluo.usagebillingenginejava.service.dto.IngestResult;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 用量事件采集入口。
 * 幂等约定：eventId 由上报方提供并全局唯一；同 eventId 重放返回同样的结论与原因，
 * 仓储中永远只有一条记录，故重复/乱序/迟到都不会造成重复计量。
 * 乱序（到达顺序与 occurredAt 不一致）与迟到（跨周期才到）在开票聚合时处理，
 * 采集阶段只做归属校验，不按到达时间丢弃。
 */
@Service
public class UsageIngestionService {

    private static final Logger log = LoggerFactory.getLogger(UsageIngestionService.class);

    private final UsageEventRepository eventRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final Clock clock;

    public UsageIngestionService(UsageEventRepository eventRepository,
                                 SubscriptionRepository subscriptionRepository, Clock clock) {
        this.eventRepository = eventRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.clock = clock;
    }

    public IngestResult ingest(String eventId, String subscriptionId, String meter,
                               long quantity, String occurredAtIso) {
        log.info("用量上报输入: eventId={}, subscriptionId={}, meter={}, quantity={}, occurredAt={}",
                eventId, subscriptionId, meter, quantity, occurredAtIso);

        if (eventId == null || eventId.isBlank()) {
            return reject(eventId, "eventId 缺失（幂等键必填）");
        }
        if (subscriptionId == null || subscriptionId.isBlank()) {
            return reject(eventId, "subscriptionId 缺失");
        }
        if (meter == null || meter.isBlank()) {
            return reject(eventId, "meter 缺失");
        }
        if (quantity <= 0) {
            return reject(eventId, "quantity 必须为正数，实际=" + quantity);
        }
        Instant occurredAt;
        try {
            occurredAt = occurredAtIso == null ? Instant.now(clock) : Instant.parse(occurredAtIso);
        } catch (DateTimeParseException e) {
            return reject(eventId, "occurredAt 不是合法 ISO-8601 时间: " + occurredAtIso);
        }
        if (occurredAt.isAfter(Instant.now(clock))) {
            return reject(eventId, "occurredAt 不能晚于当前时间: " + occurredAt);
        }
        if (subscriptionRepository.findById(subscriptionId).isEmpty()) {
            return reject(eventId, "订阅不存在: " + subscriptionId);
        }

        var existing = eventRepository.findByEventId(eventId);
        if (existing.isPresent()) {
            UsageEvent e = existing.get();
            boolean same = e.subscriptionId().equals(subscriptionId)
                    && e.meter().equals(meter) && e.quantity() == quantity
                    && e.occurredAt().equals(occurredAt);
            String reason = same
                    ? "重复上报：eventId 已存在，返回首次结论，不重复计量"
                    : "冲突上报：eventId 已存在但内容不同，拒绝并保留首次事件";
            log.info("判定 DUPLICATE: eventId={}, samePayload={}, 依据={}", eventId, same, reason);
            return new IngestResult(eventId, IngestStatus.DUPLICATE, reason, true);
        }

        UsageEvent event = new UsageEvent(eventId, subscriptionId, meter, quantity,
                occurredAt, Instant.now(clock));
        boolean inserted = eventRepository.insertIfAbsent(event);
        if (!inserted) {
            // 并发首报竞争落败：与重复上报等价，结论一致、幂等。
            String reason = "并发重复上报：eventId 已被另一请求写入，不重复计量";
            log.info("判定 DUPLICATE(并发): eventId={}, 依据={}", eventId, reason);
            return new IngestResult(eventId, IngestStatus.DUPLICATE, reason, true);
        }
        log.info("判定 ACCEPTED: eventId={}, meter={}, quantity={}, occurredAt={}, receivedAt={}",
                eventId, meter, quantity, occurredAt, event.receivedAt());
        return new IngestResult(eventId, IngestStatus.ACCEPTED,
                "首次上报，已按 occurredAt 计入对应周期", true);
    }

    private IngestResult reject(String eventId, String reason) {
        log.info("判定 REJECTED: eventId={}, 依据={}", eventId, reason);
        return new IngestResult(eventId, IngestStatus.REJECTED, reason, true);
    }
}
