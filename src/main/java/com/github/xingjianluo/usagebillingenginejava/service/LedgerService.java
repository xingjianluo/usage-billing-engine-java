package com.github.xingjianluo.usagebillingenginejava.service;

import com.github.xingjianluo.usagebillingenginejava.domain.LedgerEntry;
import com.github.xingjianluo.usagebillingenginejava.domain.LedgerEntryType;
import com.github.xingjianluo.usagebillingenginejava.domain.Money;
import com.github.xingjianluo.usagebillingenginejava.repo.LedgerRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 账本服务（append-only）。
 * 金额符号：三类分录金额均为非负，方向由类型表达。
 * 守恒式（单订阅全周期）：SUM(CHARGE) = SUM(CREDIT) + SUM(REFUND)。
 * 含义：向客户收取的总额，恒等于其账单净额被信用抵扣的部分与真正退回的部分之和；
 * 任何支付/退款路径都必须同时维持该等式，从而杜绝重复扣费与漏计。
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private final LedgerRepository ledgerRepository;
    private final Clock clock;

    public LedgerService(LedgerRepository ledgerRepository, Clock clock) {
        this.ledgerRepository = ledgerRepository;
        this.clock = clock;
    }

    public void post(String subscriptionId, LedgerEntryType type, Money amount,
                     String invoiceId, String reason) {
        if (amount == null || amount.isNegative()) {
            throw new IllegalArgumentException("账本金额不能为负: " + amount);
        }
        LedgerEntry entry = new LedgerEntry(UUID.randomUUID().toString(), subscriptionId,
                type, amount, invoiceId, reason, Instant.now(clock));
        ledgerRepository.save(entry);
        log.info("账本入账: subscriptionId={}, type={}, amount={}, invoiceId={}, reason={}, "
                        + "入账后守恒={}",
                subscriptionId, type, amount, invoiceId, reason, isConserved(subscriptionId));
    }

    public Money sum(String subscriptionId, LedgerEntryType type) {
        return ledgerRepository.findBySubscription(subscriptionId).stream()
                .filter(e -> e.type() == type)
                .map(LedgerEntry::amount)
                .reduce(Money.zero(), Money::plus);
    }

    public boolean isConserved(String subscriptionId) {
        Money charge = sum(subscriptionId, LedgerEntryType.CHARGE);
        Money credit = sum(subscriptionId, LedgerEntryType.CREDIT);
        Money refund = sum(subscriptionId, LedgerEntryType.REFUND);
        return charge.equals(credit.plus(refund));
    }
}
