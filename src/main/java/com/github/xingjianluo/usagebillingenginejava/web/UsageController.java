package com.github.xingjianluo.usagebillingenginejava.web;

import com.github.xingjianluo.usagebillingenginejava.model.IngestResult;
import com.github.xingjianluo.usagebillingenginejava.model.UsageEvent;
import com.github.xingjianluo.usagebillingenginejava.metering.UsageService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/usage")
public class UsageController {

    private final UsageService usageService;

    public UsageController(UsageService usageService) {
        this.usageService = usageService;
    }

    public record ReportRequest(String eventId, String accountId, String metric,
                                Long quantity, Instant occurredAt) {}

    @PostMapping("/events")
    public Map<String, Object> report(@RequestBody ReportRequest request) {
        UsageEvent event = new UsageEvent(request.eventId(), request.accountId(), request.metric(),
                request.quantity() == null ? 0 : request.quantity(),
                request.occurredAt(), Instant.now());
        IngestResult result = usageService.ingest(event);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", request.eventId());
        body.put("result", result.name());
        body.put("deduplicated", result == IngestResult.DUPLICATE);
        return body;
    }

    @GetMapping("/accounts/{accountId}/events")
    public List<UsageEvent> list(@PathVariable String accountId) {
        return usageService.listEvents(accountId);
    }
}
