package com.github.xingjianluo.usagebillingenginejava.repo;

import com.github.xingjianluo.usagebillingenginejava.domain.BillingPeriod;
import com.github.xingjianluo.usagebillingenginejava.domain.Invoice;
import java.util.Optional;

public interface InvoiceRepository {

    /** 周期唯一约束：已存在则返回空（由调用方取既有发票）。 */
    Optional<Invoice> insertIfAbsent(Invoice invoice);

    Optional<Invoice> findById(String id);

    Optional<Invoice> findBySubscriptionAndPeriod(String subscriptionId, BillingPeriod period);
}
