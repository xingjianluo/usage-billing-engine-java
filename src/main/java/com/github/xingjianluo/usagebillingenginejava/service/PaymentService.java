package com.github.xingjianluo.usagebillingenginejava.service;

import com.github.xingjianluo.usagebillingenginejava.domain.Invoice;
import com.github.xingjianluo.usagebillingenginejava.domain.InvoiceStatus;
import com.github.xingjianluo.usagebillingenginejava.domain.LedgerEntry;
import com.github.xingjianluo.usagebillingenginejava.domain.LedgerEntryType;
import com.github.xingjianluo.usagebillingenginejava.domain.Money;
import com.github.xingjianluo.usagebillingenginejava.domain.PaymentFailureReason;
import com.github.xingjianluo.usagebillingenginejava.repo.InvoiceRepository;
import com.github.xingjianluo.usagebillingenginejava.repo.LedgerRepository;
import com.github.xingjianluo.usagebillingenginejava.repo.RepositoryLocks;
import com.github.xingjianluo.usagebillingenginejava.service.dto.PaymentResult;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 支付服务。
 *
 * 状态流转（见 InvoiceStatus）：
 *   OPEN --pay--> PAID（成功，终态）/ PAYMENT_FAILED（失败，可区分原因）
 *   PAYMENT_FAILED --retry--> RETRYING --结果--> PAID / PAYMENT_FAILED / MAX_RETRIES_EXCEEDED
 *   PAID --refund--> VOID（终态，记 REFUND）
 *
 * 幂等与一致性：
 * - 发票级互斥锁串行化 pay/retry/refund；对 PAID/终态发票重复调用直接返回既有结论，
 *   既不重复扣款也不重复入账；
 * - 通道幂等键 = 发票号 + 第 N 次尝试，网络重试不会在通道侧重复扣款；
 * - 仅支付成功才登记账本：正向行登 CHARGE，抵扣行登 CREDIT；
 *   退款按实收净额登 REFUND，使单发票满足 CHARGE = CREDIT + REFUND；
 * - 失败达到 MAX_ATTEMPTS 进入 MAX_RETRIES_EXCEEDED 终态，保留最后失败原因，
 *   不静默丢失；终态上任何操作都不再产生交易。
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    /** 最大支付尝试次数（首次 pay + 后续 retry）。 */
    static final int MAX_ATTEMPTS = 3;

    private final InvoiceRepository invoiceRepository;
    private final LedgerRepository ledgerRepository;
    private final PaymentGateway paymentGateway;
    private final LedgerService ledgerService;
    private final RepositoryLocks locks;
    private final Clock clock;

    public PaymentService(InvoiceRepository invoiceRepository, LedgerRepository ledgerRepository,
                          PaymentGateway paymentGateway, LedgerService ledgerService,
                          RepositoryLocks locks, Clock clock) {
        this.invoiceRepository = invoiceRepository;
        this.ledgerRepository = ledgerRepository;
        this.paymentGateway = paymentGateway;
        this.ledgerService = ledgerService;
        this.locks = locks;
        this.clock = clock;
    }

    public PaymentResult pay(String invoiceId) {
        return attempt(invoiceId, false);
    }

    public PaymentResult retry(String invoiceId) {
        return attempt(invoiceId, true);
    }

    private PaymentResult attempt(String invoiceId, boolean isRetry) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("发票不存在: " + invoiceId));
        ReentrantLock lock = locks.forKey("inv:" + invoiceId);
        lock.lock();
        try {
            log.info("支付输入: invoiceId={}, action={}, status={}, attemptCount={}, total={}",
                    invoiceId, isRetry ? "RETRY" : "PAY", invoice.status(),
                    invoice.attemptCount(), invoice.total());

            // 终态/已成功：一律幂等返回，不产生任何新交易。
            if (invoice.status() == InvoiceStatus.PAID) {
                log.info("发票已支付，幂等返回成功（不重复扣款/入账）: invoiceId={}", invoiceId);
                return snapshot(invoice, true, PaymentFailureReason.NONE);
            }
            if (invoice.status() == InvoiceStatus.VOID
                    || invoice.status() == InvoiceStatus.MAX_RETRIES_EXCEEDED) {
                log.info("发票处于终态 {}，拒绝再次交易: invoiceId={}, lastReason={}",
                        invoice.status(), invoiceId, invoice.lastFailureReason());
                return snapshot(invoice, false, invoice.lastFailureReason());
            }
            if (isRetry && invoice.status() != InvoiceStatus.PAYMENT_FAILED) {
                log.info("RETRY 仅允许在 PAYMENT_FAILED 状态发起，当前={}，返回现状不扣款",
                        invoice.status());
                return snapshot(invoice, false, invoice.lastFailureReason());
            }
            if (!isRetry && invoice.status() != InvoiceStatus.OPEN) {
                log.info("PAY 仅允许在 OPEN 状态发起，当前={}，请走重试入口，本次不扣款",
                        invoice.status());
                return snapshot(invoice, false, invoice.lastFailureReason());
            }

            if (isRetry) {
                invoice.transitionTo(InvoiceStatus.RETRYING, Instant.now(clock));
            }
            int attemptNo = invoice.attemptCount() + 1;
            String key = "PAY:" + invoiceId + ":" + attemptNo;
            PaymentGateway.PaymentGatewayResponse response = paymentGateway.charge(
                    accountOf(invoice), invoice.total(), key);

            invoice.recordPaymentResult(response.success(), response.failureReason(),
                    response.reference(), Instant.now(clock));
            if (response.success()) {
                invoice.transitionTo(InvoiceStatus.PAID, Instant.now(clock));
                postInvoiceLedger(invoice);
                log.info("支付成功: invoiceId={}, attemptNo={}, gatewayRef={}, 状态=PAID",
                        invoiceId, attemptNo, response.reference());
                return snapshot(invoice, true, PaymentFailureReason.NONE);
            }

            InvoiceStatus next = invoice.attemptCount() >= MAX_ATTEMPTS
                    ? InvoiceStatus.MAX_RETRIES_EXCEEDED : InvoiceStatus.PAYMENT_FAILED;
            invoice.transitionTo(next, Instant.now(clock));
            log.info("支付失败: invoiceId={}, attemptNo={}, 可区分原因={}, 新状态={}, 未入账",
                    invoiceId, attemptNo, response.failureReason(), next);
            return snapshot(invoice, false, response.failureReason());
        } finally {
            lock.unlock();
        }
    }

    /**
     * 退还已支付发票（全额按实收净额退回，本地模拟退款，无外部依赖）。
     * 退款后发票 VOID（终态），账本满足 CHARGE = CREDIT + REFUND。
     */
    public PaymentResult refund(String invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("发票不存在: " + invoiceId));
        ReentrantLock lock = locks.forKey("inv:" + invoiceId);
        lock.lock();
        try {
            log.info("退款输入: invoiceId={}, status={}", invoiceId, invoice.status());
            if (invoice.status() == InvoiceStatus.VOID) {
                log.info("发票已作废退款，幂等返回: invoiceId={}", invoiceId);
                return snapshot(invoice, true, PaymentFailureReason.NONE);
            }
            if (invoice.status() != InvoiceStatus.PAID) {
                throw new IllegalStateException("仅 PAID 发票可退款，当前状态=" + invoice.status());
            }
            Money net = invoice.total();
            String refundRef = "GW-REF-" + invoiceId;
            ledgerService.post(invoice.subscriptionId(), LedgerEntryType.REFUND, net,
                    invoiceId, "作废发票 " + invoiceId + " 通道退款 " + refundRef);
            invoice.transitionTo(InvoiceStatus.VOID, Instant.now(clock));
            log.info("退款完成: invoiceId={}, refundAmount={}, ref={}, 状态=VOID, 账本守恒={}",
                    invoiceId, net, refundRef, ledgerConservedForInvoice(invoiceId));
            return snapshot(invoice, true, PaymentFailureReason.NONE);
        } finally {
            lock.unlock();
        }
    }

    private void postInvoiceLedger(Invoice invoice) {
        for (var line : invoice.lines()) {
            LedgerEntryType type = line.credit() ? LedgerEntryType.CREDIT : LedgerEntryType.CHARGE;
            ledgerService.post(invoice.subscriptionId(), type, line.amount(),
                    invoice.id(), "发票 " + invoice.id() + " 行: " + line.description());
        }
        log.info("账本入账完成: invoiceId={}, CHARGE={}, CREDIT={}, 实收净额={}, 守恒(含历史)={}",
                invoice.id(), sumForInvoice(invoice, LedgerEntryType.CHARGE),
                sumForInvoice(invoice, LedgerEntryType.CREDIT), invoice.total(),
                ledgerService.isConserved(invoice.subscriptionId()));
    }

    private boolean ledgerConservedForInvoice(String invoiceId) {
        Money charge = Money.zero();
        Money credit = Money.zero();
        Money refund = Money.zero();
        for (LedgerEntry e : ledgerRepository.findByInvoice(invoiceId)) {
            switch (e.type()) {
                case CHARGE -> charge = charge.plus(e.amount());
                case CREDIT -> credit = credit.plus(e.amount());
                case REFUND -> refund = refund.plus(e.amount());
            }
        }
        return charge.equals(credit.plus(refund));
    }

    private Money sumForInvoice(Invoice invoice, LedgerEntryType type) {
        return ledgerRepository.findByInvoice(invoice.id()).stream()
                .filter(e -> e.type() == type)
                .map(LedgerEntry::amount)
                .reduce(Money.zero(), Money::plus);
    }

    private String accountOf(Invoice invoice) {
        // 本地模拟：账户号即订阅号；生产环境应经订阅仓储解析付款账户。
        return invoice.subscriptionId();
    }

    private PaymentResult snapshot(Invoice invoice, boolean success, PaymentFailureReason reason) {
        List<LedgerEntry> entries = ledgerRepository.findByInvoice(invoice.id());
        Money charge = Money.zero();
        Money credit = Money.zero();
        Money refund = Money.zero();
        for (LedgerEntry e : entries) {
            switch (e.type()) {
                case CHARGE -> charge = charge.plus(e.amount());
                case CREDIT -> credit = credit.plus(e.amount());
                case REFUND -> refund = refund.plus(e.amount());
            }
        }
        return new PaymentResult(invoice.id(), success, reason, invoice.attemptCount(),
                invoice.status(), charge, credit, refund);
    }
}
