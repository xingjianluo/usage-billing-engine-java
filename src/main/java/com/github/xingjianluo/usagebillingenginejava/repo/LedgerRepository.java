package com.github.xingjianluo.usagebillingenginejava.repo;

import com.github.xingjianluo.usagebillingenginejava.domain.LedgerEntry;
import java.util.List;

public interface LedgerRepository {
    void save(LedgerEntry entry);

    List<LedgerEntry> findBySubscription(String subscriptionId);

    List<LedgerEntry> findByInvoice(String invoiceId);
}
