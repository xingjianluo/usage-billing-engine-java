package com.github.xingjianluo.usagebillingenginejava.repo;

import com.github.xingjianluo.usagebillingenginejava.domain.BillingPeriod;
import com.github.xingjianluo.usagebillingenginejava.domain.Invoice;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/**
 * 发票内存仓储。
 * 唯一性以 (subscriptionId, periodStart) 为键：并发开票时只有一个 insert 成功，
 * 其余线程回读同一张发票，从根上保证“同一周期只有一张发票”。
 */
@Repository
public class InMemoryInvoiceRepository implements InvoiceRepository {

    private final ConcurrentHashMap<String, Invoice> byId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> uniqueKeyToId = new ConcurrentHashMap<>();

    private static String uniqueKey(String subscriptionId, BillingPeriod period) {
        return subscriptionId + "|" + period.start();
    }

    @Override
    public Optional<Invoice> insertIfAbsent(Invoice invoice) {
        String key = uniqueKey(invoice.subscriptionId(), invoice.period());
        String existing = uniqueKeyToId.putIfAbsent(key, invoice.id());
        if (existing != null) {
            return Optional.of(byId.get(existing));
        }
        byId.put(invoice.id(), invoice);
        return Optional.empty();
    }

    @Override
    public Optional<Invoice> findById(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<Invoice> findBySubscriptionAndPeriod(String subscriptionId, BillingPeriod period) {
        String id = uniqueKeyToId.get(uniqueKey(subscriptionId, period));
        return Optional.ofNullable(id).map(byId::get).filter(Objects::nonNull);
    }

    /** 仅供测试隔离使用：清空全部数据。 */
    public void clearAll() {
        byId.clear();
        uniqueKeyToId.clear();
    }
}
