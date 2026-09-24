package com.github.xingjianluo.usagebillingenginejava.service;

import com.github.xingjianluo.usagebillingenginejava.domain.BillingPeriod;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import org.springframework.stereotype.Service;

/**
 * 计费周期工具：固定按 UTC 自然月切分，[月初 00:00, 下月初 00:00)。
 * 周期边界不依赖服务器默认时区，保证输入相同则归属结论相同（可重放）。
 */
@Service
public class PeriodService {

    private final Clock clock;

    public PeriodService(Clock clock) {
        this.clock = clock;
    }

    public BillingPeriod currentPeriod() {
        return periodContaining(Instant.now(clock));
    }

    public BillingPeriod periodContaining(Instant instant) {
        return periodOf(LocalDate.ofInstant(instant, ZoneOffset.UTC));
    }

    public BillingPeriod periodOf(LocalDate anyDateInPeriod) {
        YearMonth ym = YearMonth.from(anyDateInPeriod);
        return new BillingPeriod(ym.atDay(1), ym.plusMonths(1).atDay(1));
    }

    public Instant startInstant(BillingPeriod period) {
        return period.start().atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    public Instant endInstant(BillingPeriod period) {
        return period.end().atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
