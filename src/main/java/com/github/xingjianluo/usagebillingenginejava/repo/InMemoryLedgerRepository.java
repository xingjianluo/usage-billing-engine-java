package com.github.xingjianluo.usagebillingenginejava.repo;

import com.github.xingjianluo.usagebillingenginejava.domain.LedgerEntry;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Repository;

/**
 * 账本内存仓储：分录只追加，不修改不删除（append-only）。
 */
@Repository
public class InMemoryLedgerRepository implements LedgerRepository {

    private final CopyOnWriteArrayList<LedgerEntry> entries = new CopyOnWriteArrayList<>();

    @Override
    public void save(LedgerEntry entry) {
        entries.add(entry);
    }

    @Override
    public List<LedgerEntry> findBySubscription(String subscriptionId) {
        return entries.stream()
                .filter(e -> e.subscriptionId().equals(subscriptionId))
                .toList();
    }

    @Override
    public List<LedgerEntry> findByInvoice(String invoiceId) {
        return entries.stream()
                .filter(e -> invoiceId.equals(e.invoiceId()))
                .toList();
    }

    /** 仅供测试隔离使用：清空全部数据。 */
    public void clearAll() {
        entries.clear();
    }
}
