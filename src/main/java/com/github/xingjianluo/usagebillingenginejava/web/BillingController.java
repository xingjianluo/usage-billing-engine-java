package com.github.xingjianluo.usagebillingenginejava.web;

import com.github.xingjianluo.usagebillingenginejava.domain.BillingPeriod;
import com.github.xingjianluo.usagebillingenginejava.service.InvoiceService;
import com.github.xingjianluo.usagebillingenginejava.service.PaymentService;
import com.github.xingjianluo.usagebillingenginejava.service.PeriodService;
import com.github.xingjianluo.usagebillingenginejava.service.SubscriptionService;
import com.github.xingjianluo.usagebillingenginejava.service.LedgerService;
import com.github.xingjianluo.usagebillingenginejava.service.UsageIngestionService;
import com.github.xingjianluo.usagebillingenginejava.domain.LedgerEntryType;
import com.github.xingjianluo.usagebillingenginejava.domain.Money;
import com.github.xingjianluo.usagebillingenginejava.web.dto.IssueInvoiceRequest;
import com.github.xingjianluo.usagebillingenginejava.web.dto.PlanChangeRequest;
import com.github.xingjianluo.usagebillingenginejava.web.dto.SubscriptionRequest;
import com.github.xingjianluo.usagebillingenginejava.web.dto.UsageEventRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class BillingController {

    private final UsageIngestionService usageIngestionService;
    private final SubscriptionService subscriptionService;
    private final InvoiceService invoiceService;
    private final PaymentService paymentService;
    private final PeriodService periodService;
    private final LedgerService ledgerService;
    private final Clock clock;

    public BillingController(UsageIngestionService usageIngestionService,
                             SubscriptionService subscriptionService,
                             InvoiceService invoiceService,
                             PaymentService paymentService,
                             PeriodService periodService,
                             LedgerService ledgerService, Clock clock) {
        this.usageIngestionService = usageIngestionService;
        this.subscriptionService = subscriptionService;
        this.invoiceService = invoiceService;
        this.paymentService = paymentService;
        this.periodService = periodService;
        this.ledgerService = ledgerService;
        this.clock = clock;
    }

    @PostMapping("/usage-events")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Object ingest(@RequestBody UsageEventRequest request) {
        return usageIngestionService.ingest(request.eventId(), request.subscriptionId(),
                request.meter(), request.quantity() == null ? 0 : request.quantity(),
                request.occurredAt());
    }

    @PostMapping("/subscriptions")
    @ResponseStatus(HttpStatus.CREATED)
    public com.github.xingjianluo.usagebillingenginejava.service.dto.SubscriptionView
            createSubscription(@RequestBody SubscriptionRequest request) {
        var sub = subscriptionService.createSubscription(request.subscriptionId(),
                request.accountId(), request.planCode(), request.createdAt());
        var versions = sub.versions().stream()
                .map(v -> new com.github.xingjianluo.usagebillingenginejava.service.dto
                        .SubscriptionView.VersionView(v.planCode(), v.effectiveAt().toString()))
                .toList();
        return new com.github.xingjianluo.usagebillingenginejava.service.dto.SubscriptionView(
                sub.id(), sub.accountId(), sub.createdAt().toString(), versions);
    }

    @PostMapping("/subscriptions/{id}/plan-change")
    public Object changePlan(@PathVariable String id, @RequestBody PlanChangeRequest request) {
        return subscriptionService.changePlan(id, request.planCode(), request.effectiveAt());
    }

    @PostMapping("/subscriptions/{id}/invoices")
    public Object issueInvoice(@PathVariable String id, @RequestBody(required = false)
                               IssueInvoiceRequest request) {
        LocalDate anchor = request == null || request.period() == null
                ? null : LocalDate.parse(request.period());
        BillingPeriod period = anchor == null
                ? periodService.periodContaining(Instant.now(clock))
                : periodService.periodOf(anchor);
        return invoiceService.issue(id, period);
    }

    @PostMapping("/invoices/{invoiceId}/pay")
    public Object pay(@PathVariable String invoiceId) {
        return paymentService.pay(invoiceId);
    }

    @PostMapping("/invoices/{invoiceId}/retry")
    public Object retry(@PathVariable String invoiceId) {
        return paymentService.retry(invoiceId);
    }

    @PostMapping("/invoices/{invoiceId}/refund")
    public Object refund(@PathVariable String invoiceId) {
        return paymentService.refund(invoiceId);
    }

    @GetMapping("/invoices/{invoiceId}")
    public com.github.xingjianluo.usagebillingenginejava.service.dto.InvoiceView
            getInvoice(@PathVariable String invoiceId) {
        return com.github.xingjianluo.usagebillingenginejava.service.dto.InvoiceView
                .from(invoiceService.getInvoice(invoiceId));
    }

    @GetMapping("/subscriptions/{id}/ledger")
    public LedgerView ledger(@PathVariable String id) {
        Money charge = ledgerService.sum(id, LedgerEntryType.CHARGE);
        Money credit = ledgerService.sum(id, LedgerEntryType.CREDIT);
        Money refund = ledgerService.sum(id, LedgerEntryType.REFUND);
        return new LedgerView(id, charge, credit, refund,
                charge.equals(credit.plus(refund)));
    }
}
