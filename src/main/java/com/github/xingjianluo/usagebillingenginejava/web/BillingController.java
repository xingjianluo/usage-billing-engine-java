package com.github.xingjianluo.usagebillingenginejava.web;

import com.github.xingjianluo.usagebillingenginejava.invoicing.InvoiceService;
import com.github.xingjianluo.usagebillingenginejava.model.Invoice;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/billing")
public class BillingController {

    private final InvoiceService invoiceService;

    public BillingController(InvoiceService invoiceService) {
        this.invoiceService = invoiceService;
    }

    public record IssueRequest(String accountId, Instant periodStart) {}

    @PostMapping("/invoices")
    public Invoice issue(@RequestBody IssueRequest request) {
        if (request.accountId() == null || request.periodStart() == null) {
            throw new IllegalArgumentException("accountId 与 periodStart 均不能为空");
        }
        return invoiceService.issue(request.accountId(), request.periodStart());
    }

    @GetMapping("/accounts/{accountId}/invoices")
    public Invoice get(@PathVariable String accountId, @RequestParam Instant periodStart) {
        return invoiceService.get(accountId, periodStart);
    }

    @PostMapping("/invoices/{invoiceId}/pay")
    public Map<String, Object> pay(@PathVariable String invoiceId) {
        return describe(invoiceService.pay(invoiceId));
    }

    @PostMapping("/invoices/{invoiceId}/retry")
    public Map<String, Object> retry(@PathVariable String invoiceId) {
        return describe(invoiceService.runRetries(invoiceId));
    }

    private Map<String, Object> describe(Invoice invoice) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("invoiceId", invoice.invoiceId());
        body.put("status", invoice.status().name());
        body.put("amount", invoice.amount().toString());
        body.put("attemptCount", invoice.attemptCount());
        body.put("lastFailureReason",
                invoice.lastFailureReason() == null ? null : invoice.lastFailureReason().name());
        return body;
    }
}
