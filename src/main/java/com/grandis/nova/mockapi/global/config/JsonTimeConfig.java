package com.grandis.nova.mockapi.global.config;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;

/**
 * 응답 시각을 항상 밀리초 세 자리로 쓴다 — {@code 2026-09-16T10:00:03.000Z}.
 *
 * <p>Jackson 기본값(ISO_INSTANT)은 밀리초가 0 이면 소수부를 빼고 {@code ...:03Z} 로 쓴다. 저장 시각을 밀리초로
 * 자르므로 천 번에 한 번꼴로 모양이 달라지고, 명세 예시({@code .000Z})와도 다르다. 받는 쪽이 고정 길이나
 * 정규식으로 읽으면 그때만 깨져 재현이 어렵다. 등록 · 제어 API 의 모든 시각에 걸린다.
 */
@Configuration
public class JsonTimeConfig {

    static final DateTimeFormatter MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    @Bean
    JsonMapperBuilderCustomizer millisInstant() {
        return builder -> builder.addModule(new SimpleModule("millis-instant")
                .addSerializer(Instant.class, new ValueSerializer<Instant>() {
                    @Override
                    public void serialize(Instant value, JsonGenerator generator, SerializationContext context) {
                        generator.writeString(MILLIS.format(value));
                    }
                }));
    }
}
