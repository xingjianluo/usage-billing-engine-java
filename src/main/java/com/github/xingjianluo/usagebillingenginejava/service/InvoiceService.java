package com.github.xingjianluo.usagebillingenginejava.service;

import com.github.xingjianluo.usagebillingenginejava.domain.BillingPeriod;
import com.github.xingjianluo.usagebillingenginejava.domain.Invoice;
import com.github.xingjianluo.usagebillingenginejava.domain.InvoiceLineItem;
import com.github.xingjianluo.usagebillingenginejava.domain.InvoiceStatus;
import com.github.xingjianluo.usagebillingenginejava.domain.Subscription;
import com.github.xingjianluo.usagebillingenginejava.repo.InvoiceRepository;
import com.github.xingjianluo.usagebillingenginejava.repo.RepositoryLocks;
import com.github.xingjianluo.usagebillingenginejava.repo.SubscriptionRepository;
import com.github.xingjianluo.usagebillingenginejava.service.dto.BillingCalculation;
import com.github.xingjianluo.usagebillingenginejava.service.dto.IssueInvoiceResult;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 发票服务。
 *
 * 开票模型（后付费，按自然月）：整期费用（基础费按版本时间片分摊 + 用量费按片阶梯）由
 * {@link BillingCalculator} 统一计算；结算后补差复用同一计算器，保证两口径严格一致。
 *
 * 幂等：(subscriptionId, period) 唯一约束 + 订阅级锁，并发或重试调用只产生一张发票，
 * 后续调用返回 created=false 的既有发票。金额由确定性输入计算，重放金额可复现。
 */
@Service
public class InvoiceService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceService.class);

    private final InvoiceRepository invoiceRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PeriodService periodService;
    private final BillingCalculator billingCalculator;
    private final RepositoryLocks locks;
    private final Clock clock;

    public InvoiceService(InvoiceRepository invoiceRepository,
                          SubscriptionRepository subscriptionRepository,
                          PeriodService periodService,
                          BillingCalculator billingCalculator,
                          RepositoryLocks locks, Clock clock) {
        this.invoiceRepository = invoiceRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.periodService = periodService;
        this.billingCalculator = billingCalculator;
        this.locks = locks;
        this.clock = clock;
    }

    public IssueInvoiceResult issue(String subscriptionId, BillingPeriod period) {
        if (period == null) {
            period = periodService.currentPeriod();
        }
        log.info("开票输入: subscriptionId={}, period=[{} {})", subscriptionId,
                period.start(), period.end());
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new IllegalArgumentException("订阅不存在: " + subscriptionId));
        final BillingPeriod p = period;

        ReentrantLock lock = locks.forKey("sub:" + subscriptionId);
        lock.lock();
        try {
            var existing = invoiceRepository.findBySubscriptionAndPeriod(subscriptionId, p);
            if (existing.isPresent()) {
                Invoice inv = existing.get();
                log.info("发票已存在，幂等返回: invoiceId={}, status={}, total={}（不重复开票）",
                        inv.id(), inv.status(), inv.total());
                return toResult(inv, false);
            }

            BillingCalculation calc = billingCalculator.calculate(subscription, p);
            List<InvoiceLineItem> lines = calc.lines();
            Invoice invoice = new Invoice(UUID.randomUUID().toString(), subscriptionId, p,
                    lines, Instant.now(clock));
            invoice.transitionTo(InvoiceStatus.OPEN, Instant.now(clock));

            Invoice concurrent = invoiceRepository.insertIfAbsent(invoice).orElse(null);
            if (concurrent != null) {
                log.info("并发开票落败，采用已插入发票: invoiceId={}, total={}",
                        concurrent.id(), concurrent.total());
                return toResult(concurrent, false);
            }
            log.info("发票已生成: invoiceId={}, 行数={}, 合计={}", invoice.id(),
                    lines.size(), invoice.total());
            lines.forEach(l -> log.info("  发票行: type={}, credit={}, amount={}, desc={}",
                    l.type(), l.credit(), l.amount(), l.description()));
            return toResult(invoice, true);
        } finally {
            lock.unlock();
        }
    }

    public Invoice getInvoice(String invoiceId) {
        return invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("发票不存在: " + invoiceId));
    }

    private IssueInvoiceResult toResult(Invoice invoice, boolean created) {
        return new IssueInvoiceResult(invoice.id(), invoice.subscriptionId(),
                invoice.period().start().toString(), invoice.period().end().toString(),
                invoice.total(), invoice.status(), created);
    }
}
