package com.github.xingjianluo.usagebillingenginejava.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 统一时钟：生产使用系统 UTC 时钟；测试可注入固定 Clock 实现确定性重放。
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("UTC"));
    }
}
