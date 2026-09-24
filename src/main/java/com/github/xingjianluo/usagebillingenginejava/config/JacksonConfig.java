package com.github.xingjianluo.usagebillingenginejava.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.github.xingjianluo.usagebillingenginejava.model.Money;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.math.BigDecimal;

/**
 * Jackson 定制：金额在 JSON 中统一以两位小数字符串表达（如 "149.00"），
 * 避免 JSON number 解析时尾零丢失（149.00 -> 149.0）造成的“金额看起来不一致”。
 */
@Configuration
public class JacksonConfig {

    @Bean
    Jackson2ObjectMapperBuilderCustomizer moneyCustomizer() {
        return builder -> {
            builder.serializerByType(BigDecimal.class, new JsonSerializer<BigDecimal>() {
                @Override
                public void serialize(BigDecimal value, JsonGenerator gen, SerializerProvider serializers)
                        throws IOException {
                    gen.writeString(value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
                }
            });
            builder.serializerByType(Money.class, new JsonSerializer<Money>() {
                @Override
                public void serialize(Money value, JsonGenerator gen, SerializerProvider serializers)
                        throws IOException {
                    gen.writeStartObject();
                    gen.writeStringField("currency", "CNY");
                    gen.writeStringField("amount", value.amount().toPlainString());
                    gen.writeEndObject();
                }
            });
        };
    }
}
