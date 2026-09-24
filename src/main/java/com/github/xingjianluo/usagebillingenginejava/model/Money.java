package com.github.xingjianluo.usagebillingenginejava.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 金额值对象，统一使用 BigDecimal，单位：元。
 * 一律保留两位小数（HALF_UP），避免浮点误差导致重放金额不一致。
 */
public record Money(BigDecimal amount) {

    public static final Money ZERO = new Money(BigDecimal.ZERO);

    public Money {
        Objects.requireNonNull(amount, "amount");
        amount = amount.setScale(2, RoundingMode.HALF_UP);
    }

    public static Money of(String value) {
        return new Money(new BigDecimal(value));
    }

    public static Money of(long value) {
        return new Money(BigDecimal.valueOf(value));
    }

    public Money add(Money other) {
        return new Money(amount.add(other.amount));
    }

    public Money subtract(Money other) {
        return new Money(amount.subtract(other.amount));
    }

    public Money negate() {
        return new Money(amount.negate());
    }

    public Money multiply(long factor) {
        return new Money(amount.multiply(BigDecimal.valueOf(factor)));
    }

    /** 按比例分摊：this * numerator / denominator，结果两位小数，尾差由调用方校正。 */
    public Money prorate(long numerator, long denominator) {
        if (denominator <= 0 || numerator <= 0) {
            return ZERO;
        }
        return new Money(amount.multiply(BigDecimal.valueOf(numerator))
                .divide(BigDecimal.valueOf(denominator), RoundingMode.HALF_UP));
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    @Override
    public String toString() {
        return amount.toPlainString();
    }
}
