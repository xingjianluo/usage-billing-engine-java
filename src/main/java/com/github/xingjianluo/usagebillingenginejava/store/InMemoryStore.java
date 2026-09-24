package com.github.xingjianluo.usagebillingenginejava.store;

import com.github.xingjianluo.usagebillingenginejava.model.Bill;
import com.github.xingjianluo.usagebillingenginejava.model.Invoice;
import com.github.xingjianluo.usagebillingenginejava.model.LedgerEntry;
import com.github.xingjianluo.usagebillingenginejava.model.Subscription;
import com.github.xingjianluo.usagebillingenginejava.model.UsageEvent;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 本地内存存储（模拟单节点数据库）。
 *
 * 并发约定：
 * - 所有 Map 均为 {@link ConcurrentHashMap}，保证单操作可见性；
 * - “先检查后写入”类复合操作（事件去重、开票、支付状态机）必须先取
 *   {@link #lockFor(String)} 账号锁，保证同一账号上的判定与落盘原子；
 * - 账本 append 本身原子，业务幂等（同发票仅一条收入）由调用方在账号锁内保证。
 */
@Component
public class InMemoryStore {

    private final Map<String, UsageEvent> events = new ConcurrentHashMap<>();
    /** 被判定 REJECTED（未来时间/超窗迟到）的事件 id：保留可审计，但聚合必须排除。 */
    private final java.util.Set<String> rejectedEventIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final Map<String, Bill> bills = new ConcurrentHashMap<>();
    private final Map<String, Invoice> invoices = new ConcurrentHashMap<>();
    private final List<LedgerEntry> ledger = java.util.Collections.synchronizedList(new ArrayList<>());
    private final Map<String, ReentrantLock> accountLocks = new ConcurrentHashMap<>();

    /** 同一账号始终拿到同一把锁；锁对象惰性创建且不删除。 */
    public ReentrantLock lockFor(String accountId) {
        return accountLocks.computeIfAbsent(accountId, k -> new ReentrantLock());
    }

    /**
     * 事件幂等写入：eventId 已存在返回 false（重复/乱序重放），
     * 否则写入返回 true。putIfAbsent 本身原子。
     */
    public boolean putEventIfAbsent(UsageEvent event) {
        return events.putIfAbsent(event.eventId(), event) == null;
    }

    public UsageEvent getEvent(String eventId) {
        return events.get(eventId);
    }

    public List<UsageEvent> eventsByAccount(String accountId) {
        return events.values().stream()
                .filter(e -> e.accountId().equals(accountId))
                .toList();
    }

    public void markRejected(String eventId) {
        rejectedEventIds.add(eventId);
    }

    public boolean isRejected(String eventId) {
        return rejectedEventIds.contains(eventId);
    }

    public void saveSubscription(Subscription subscription) {
        subscriptions.put(subscription.accountId(), subscription);
    }

    public Subscription getSubscription(String accountId) {
        return subscriptions.get(accountId);
    }

    public String billKey(String accountId, Instant periodStart) {
        return accountId + "|" + periodStart;
    }

    public Bill getBill(String key) {
        return bills.get(key);
    }

    public void putBill(String key, Bill bill) {
        bills.put(key, bill);
    }

    public String invoiceKey(String accountId, Instant periodStart) {
        return accountId + "|" + periodStart;
    }

    public Invoice getInvoice(String key) {
        return invoices.get(key);
    }

    public void putInvoice(String key, Invoice invoice) {
        invoices.put(key, invoice);
    }

    public Collection<Invoice> allInvoices() {
        return List.copyOf(invoices.values());
    }

    public Invoice getInvoiceById(String invoiceId) {
        return invoices.values().stream()
                .filter(i -> i.invoiceId().equals(invoiceId))
                .findFirst()
                .orElse(null);
    }

    public void appendLedger(LedgerEntry entry) {
        ledger.add(entry);
    }

    public List<LedgerEntry> ledgerByInvoice(String invoiceId) {
        return ledger.stream()
                .filter(e -> e.invoiceId().equals(invoiceId))
                .toList();
    }

    public List<LedgerEntry> allLedger() {
        return List.copyOf(ledger);
    }
}
