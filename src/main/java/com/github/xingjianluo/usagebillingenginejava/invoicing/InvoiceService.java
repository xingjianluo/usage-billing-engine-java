package com.github.xingjianluo.usagebillingenginejava.invoicing;

import com.github.xingjianluo.usagebillingenginejava.billing.BillingService;
import com.github.xingjianluo.usagebillingenginejava.ledger.LedgerService;
import com.github.xingjianluo.usagebillingenginejava.model.Bill;
import com.github.xingjianluo.usagebillingenginejava.model.Invoice;
import com.github.xingjianluo.usagebillingenginejava.model.InvoiceStatus;
import com.github.xingjianluo.usagebillingenginejava.model.PaymentAttempt;
import com.github.xingjianluo.usagebillingenginejava.model.PaymentFailureReason;
import com.github.xingjianluo.usagebillingenginejava.payment.PaymentGateway;
import com.github.xingjianluo.usagebillingenginejava.payment.RetryPolicy;
import com.github.xingjianluo.usagebillingenginejava.store.InMemoryStore;
import com.github.xingjianluo.usagebillingenginejava.subscription.SubscriptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 开票与支付编排。
 *
 * 发票幂等：同一 (accountId, periodStart) 在账号锁内“检查-创建”，
 * 无论多少并发请求或重试，只可能产生一张发票；失败者看到的也是同一张。
 *
 * 支付状态机：
 * <pre>
 *   DRAFT(不存在) --issue--> PENDING_PAYMENT --charge NONE--------------> PAID（终态）
 *                                          └─charge 可重试失败（次数未满）--> PENDING_PAYMENT
 *                                          └─charge 失败且次数达上限-------> FAILED（终态）
 * </pre>
 * 终态（PAID/FAILED）后的 pay/retry 被显式拒绝：PAID 防重复入账，
 * FAILED 防静默复活。失败原因（INSUFFICIENT_FUNDS / CARD_DECLINED / GATEWAY_ERROR）
 * 逐次记录，结论可区分、可解释。
 *
 * 入账守恒：只有一次 NONE 结果会触发账本 PAYMENT，且 LedgerService 再做一次幂等兜底。
 */
@Service
public class InvoiceService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceService.class);

    private final InMemoryStore store;
    private final BillingService billingService;
    private final SubscriptionService subscriptionService;
    private final PaymentGateway gateway;
    private final RetryPolicy retryPolicy;
    private final LedgerService ledgerService;

    public InvoiceService(InMemoryStore store,
                          BillingService billingService,
                          SubscriptionService subscriptionService,
                          PaymentGateway gateway,
                          RetryPolicy retryPolicy,
                          LedgerService ledgerService) {
        this.store = store;
        this.billingService = billingService;
        this.subscriptionService = subscriptionService;
        this.gateway = gateway;
        this.retryPolicy = retryPolicy;
        this.ledgerService = ledgerService;
    }

    public Invoice issue(String accountId, Instant periodStart) {
        store.lockFor(accountId).lock();
        try {
            String key = store.invoiceKey(accountId, periodStart);
            Invoice existing = store.getInvoice(key);
            if (existing != null) {
                log.info("开票幂等命中 account={} period={} invoice={} status={} -> 返回既有发票",
                        accountId, periodStart, existing.invoiceId(), existing.status());
                return existing;
            }
            Bill bill = billingService.billForPeriod(accountId, periodStart);
            Instant now = Instant.now();
            Invoice invoice = new Invoice(
                    "INV-" + UUID.randomUUID(),
                    accountId, periodStart, periodStart.plusSeconds(SubscriptionService.PERIOD_SECONDS),
                    bill.total(), InvoiceStatus.PENDING_PAYMENT,
                    PaymentFailureReason.NONE, 0, List.of(), now, now);
            store.putInvoice(key, invoice);
            log.info("发票已开立 invoice={} account={} period={} amount={}（同一周期仅此一张）",
                    invoice.invoiceId(), accountId, periodStart, invoice.amount());
            return invoice;
        } finally {
            store.lockFor(accountId).unlock();
        }
    }

    public Invoice get(String accountId, Instant periodStart) {
        return store.getInvoice(store.invoiceKey(accountId, periodStart));
    }

    /** 执行一次支付尝试。返回更新后的发票；终态调用抛出 IllegalStateException。 */
    public Invoice pay(String invoiceId) {
        Invoice invoice = requireInvoice(invoiceId);
        store.lockFor(invoice.accountId()).lock();
        try {
            invoice = requireInvoice(invoiceId); // 锁内重读
            if (invoice.status() == InvoiceStatus.PAID) {
                log.warn("支付被拒绝：发票 {} 已支付（终态），禁止重复扣款", invoiceId);
                throw new IllegalStateException("发票已支付，禁止重复支付: " + invoiceId);
            }
            if (invoice.status() == InvoiceStatus.FAILED) {
                log.warn("支付被拒绝：发票 {} 已达 FAILED 终态（原因={}），需人工处理，不会静默重试",
                        invoiceId, invoice.lastFailureReason());
                throw new IllegalStateException(
                        "发票已失败终态，不可再支付: " + invoiceId + "，最后失败原因="
                                + invoice.lastFailureReason());
            }

            int attemptNo = invoice.attemptCount() + 1;
            PaymentFailureReason reason = gateway.charge(invoice, attemptNo);
            List<PaymentAttempt> attempts = new ArrayList<>(invoice.attempts());
            attempts.add(new PaymentAttempt(attemptNo, Instant.now(),
                    reason == PaymentFailureReason.NONE, reason));

            Invoice next;
            if (reason == PaymentFailureReason.NONE) {
                next = replace(invoice, InvoiceStatus.PAID, PaymentFailureReason.NONE,
                        attemptNo, attempts);
                store.putInvoice(store.invoiceKey(invoice.accountId(), invoice.periodStart()), next);
                ledgerService.recordPayment(next); // 唯一一次成功 -> 唯一一条收入流水
                log.info("发票支付成功 invoice={} attempt={} -> PAID，已记收入 {}",
                        invoiceId, attemptNo, next.amount());
            } else if (retryPolicy.exhausted(attemptNo)) {
                next = replace(invoice, InvoiceStatus.FAILED, reason, attemptNo, attempts);
                store.putInvoice(store.invoiceKey(invoice.accountId(), invoice.periodStart()), next);
                log.error("发票支付终态失败 invoice={} attempts={} 最后原因={} -> FAILED（不再自动重试）",
                        invoiceId, attemptNo, reason);
            } else {
                next = replace(invoice, InvoiceStatus.PENDING_PAYMENT, reason, attemptNo, attempts);
                store.putInvoice(store.invoiceKey(invoice.accountId(), invoice.periodStart()), next);
                log.warn("发票支付失败待重试 invoice={} attempt={} reason={} 退避={}s",
                        invoiceId, attemptNo, reason, retryPolicy.backoffSeconds(attemptNo));
            }
            return next;
        } finally {
            store.lockFor(invoice.accountId()).unlock();
        }
    }

    /**
     * 按既定策略执行重试，直到 PAID 或 FAILED。纯循环、无真实等待。
     * 终态（PAID/FAILED）发票上的调用被显式拒绝：防止重复入账或静默复活，
     * 拒绝原因可解释，发票本身保留可查。
     */
    public Invoice runRetries(String invoiceId) {
        Invoice invoice = requireInvoice(invoiceId);
        if (invoice.status() == InvoiceStatus.PAID) {
            throw new IllegalStateException("发票已支付，无需重试: " + invoiceId);
        }
        if (invoice.status() == InvoiceStatus.FAILED) {
            throw new IllegalStateException(
                    "发票已失败终态，自动重试已终止: " + invoiceId
                            + "，最后失败原因=" + invoice.lastFailureReason() + "，请走人工处理");
        }
        int guard = 0;
        while (invoice.status() == InvoiceStatus.PENDING_PAYMENT
                && invoice.attemptCount() < RetryPolicy.MAX_ATTEMPTS && guard++ < RetryPolicy.MAX_ATTEMPTS + 1) {
            invoice = pay(invoiceId);
        }
        log.info("重试流程结束 invoice={} status={} attempts={} lastReason={}",
                invoiceId, invoice.status(), invoice.attemptCount(), invoice.lastFailureReason());
        return invoice;
    }

    private Invoice requireInvoice(String invoiceId) {
        Invoice invoice = store.getInvoiceById(invoiceId);
        if (invoice == null) {
            throw new IllegalArgumentException("发票不存在: " + invoiceId);
        }
        return invoice;
    }

    private Invoice replace(Invoice old, InvoiceStatus status, PaymentFailureReason reason,
                            int attemptCount, List<PaymentAttempt> attempts) {
        return new Invoice(old.invoiceId(), old.accountId(), old.periodStart(), old.periodEnd(),
                old.amount(), status, reason, attemptCount, List.copyOf(attempts),
                old.createdAt(), Instant.now());
    }
}
