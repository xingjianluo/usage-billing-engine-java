package com.github.xingjianluo.usagebillingenginejava.web;

import com.github.xingjianluo.usagebillingenginejava.domain.Money;

/** 账本视图：charge 应恒等于 credit + refund（conserved=true）。 */
public record LedgerView(String subscriptionId, Money charge, Money credit, Money refund,
                         boolean conserved) {
}
