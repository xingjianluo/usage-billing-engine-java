package com.github.xingjianluo.usagebillingenginejava.ledger;

import com.github.xingjianluo.usagebillingenginejava.model.Invoice;
import com.github.xingjianluo.usagebillingenginejava.model.LedgerEntry;
import com.github.xingjianluo.usagebillingenginejava.model.Money;
import com.github.xingjianluo.usagebillingenginejava.store.InMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 不可变账本服务（本地模拟总账）。
 *
 * 守恒与幂等约定：
 * - 每张发票至多一条 {@code PAYMENT} 收入流水：重复入账被检测并拒绝，
 *   杜绝支付重试成功后重复扣款入账；
 * - 退款/冲正以 {@code REFUND} 负流水追加，绝不修改历史流水；
 * - 任意时刻净额 netOf(invoiceId) = 收入 + 退款之和；
 *   未支付为 0，已支付未退为发票金额，部分退还是二者代数和——账单、信用、退款守恒。
 *
 * 调用方（InvoiceService）在账号锁内调用，保证“检查既有入账 + 写入”原子。
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private final InMemoryStore store;
    private final AtomicLong sequence = new AtomicLong();

    public LedgerService(InMemoryStore store) {
        this.store = store;
    }

    public LedgerEntry recordPayment(Invoice invoice) {
        List<LedgerEntry> existing = store.ledgerByInvoice(invoice.invoiceId());
        boolean alreadyPaid = existing.stream().anyMatch(e -> "PAYMENT".equals(e.type()));
        if (alreadyPaid) {
            log.warn("入账被拒绝：发票 {} 已存在 PAYMENT 流水（幂等保护，防止重复入账）",
                    invoice.invoiceId());
            throw new IllegalStateException("发票已入账，禁止重复入账: " + invoice.invoiceId());
        }
        LedgerEntry entry = new LedgerEntry(newEntryId(), invoice.invoiceId(), invoice.accountId(),
                invoice.amount(), "PAYMENT", Instant.now());
        store.appendLedger(entry);
        log.info("账本入账 invoice={} account={} amount={} entry={}",
                invoice.invoiceId(), invoice.accountId(), invoice.amount(), entry.entryId());
        return entry;
    }

    public LedgerEntry recordRefund(Invoice invoice, Money amount, String reason) {
        if (amount == null || amount.amount().signum() <= 0) {
            throw new IllegalArgumentException("退款金额必须为正数");
        }
        Money net = netOf(invoice.invoiceId());
        if (amount.amount().compareTo(net.amount()) > 0) {
            throw new IllegalStateException(
                    "退款超额：可退净额 " + net + "，申请退款 " + amount);
        }
        LedgerEntry entry = new LedgerEntry(newEntryId(), invoice.invoiceId(), invoice.accountId(),
                amount.negate(), "REFUND:" + reason, Instant.now());
        store.appendLedger(entry);
        log.info("账本退款 invoice={} amount={} reason={} entry={}",
                invoice.invoiceId(), amount, reason, entry.entryId());
        return entry;
    }

    public List<LedgerEntry> byInvoice(String invoiceId) {
        return store.ledgerByInvoice(invoiceId);
    }

    public List<LedgerEntry> all() {
        return store.allLedger();
    }

    public Money netOf(String invoiceId) {
        return store.ledgerByInvoice(invoiceId).stream()
                .map(LedgerEntry::amount)
                .reduce(Money.ZERO, Money::add);
    }

    private String newEntryId() {
        return "LE-" + UUID.randomUUID() + "-" + sequence.incrementAndGet();
    }
}
