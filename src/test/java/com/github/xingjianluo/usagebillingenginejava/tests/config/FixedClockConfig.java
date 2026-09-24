package com.github.xingjianluo.usagebillingenginejava.tests.config;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试固定时钟：锚定 2026-09-24（与示例周期同月），保证“当前周期”类结论确定可复现。
 */
@TestConfiguration
public class FixedClockConfig {

    @Bean
    @Primary
    public Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-09-24T12:00:00Z"), ZoneOffset.UTC);
    }
}
