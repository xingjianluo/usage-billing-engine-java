package com.github.xingjianluo.usagebillingenginejava.domain;

import com.fasterxml.jackson.annotation.JsonValue;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

/**
 * 金额值对象：内部使用 BigDecimal（分单位整数不可行——存在按比例分摊），
 * 统一 2 位小数、HALF_UP 舍入，币种 CNY。
 * 不可变，四则运算均返回新对象。
 */
public final class Money {

    public static final Currency CNY = Currency.getInstance("CNY");
    private static final int SCALE = 2;

    private final BigDecimal amount;

    public Money(BigDecimal amount) {
        this.amount = amount.setScale(SCALE, RoundingMode.HALF_UP);
    }

    public static Money of(String value) {
        return new Money(new BigDecimal(value));
    }

    public static Money zero() {
        return new Money(BigDecimal.ZERO);
    }

    @JsonValue
    public BigDecimal value() {
        return amount;
    }

    public Money plus(Money other) {
        return new Money(amount.add(other.amount));
    }

    public Money minus(Money other) {
        return new Money(amount.subtract(other.amount));
    }

    public Money multiply(BigDecimal factor) {
        return new Money(amount.multiply(factor));
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Money money)) {
            return false;
        }
        return amount.compareTo(money.amount) == 0;
    }

    @Override
    public int hashCode() {
        return amount.stripTrailingZeros().hashCode();
    }

    @Override
    public String toString() {
        return amount.toPlainString();
    }
}
