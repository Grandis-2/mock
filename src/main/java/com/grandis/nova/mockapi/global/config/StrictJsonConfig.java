package com.grandis.nova.mockapi.global.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.type.LogicalType;

/**
 * 요청 본문이 계약과 다르면 400 으로 거절한다. 모르는 필드, 타입이 다른 값, 같은 필드 두 번이 그렇다.
 *
 * <p>스프링 부트 기본값은 모르는 필드를 조용히 버린다. 설정 API 에 timeoutHoldMs 를 넣어도 200 이 나와
 * 적용된 줄 알게 되고, 워커가 계약에 없는 필드를 보내도 아무도 모른다. 같은 키로 다른 내용이 오면
 * 422 로 거절하는 것과 같은 원칙이다 — 계약 오류를 조용히 넘기지 않는다.
 *
 * <p>타입도 같다. Jackson 기본값은 {@code "1001"} → 1001, {@code 1.9} → 1, {@code 123} → {@code "123"},
 * 열거형 자리의 {@code 1} → 두 번째 값으로 바꿔 받고, 같은 필드가 두 번 오면 마지막 값을 쓴다. 워커가 타입을
 * 잘못 보내도 Mock 이 맞춰 주면 실제 외부 시스템에서야 터진다. 정수 → 실수({@code "failureRate": 1})는
 * 값이 바뀌지 않으니 받는다.
 *
 * <p>application.yml 이 아니라 코드에 둔다. application.yml 은 각자 예제를 복사해 쓰는 파일이라,
 * 예제에 한 줄 추가해도 이미 복사해둔 사본에는 들어가지 않아 기계마다 동작이 갈린다.
 *
 * <p>대신 DTO 에 명세의 필드를 전부 선언해야 한다. 저장하지 않는 필드(취소의 reason)도 마찬가지다.
 * 빠뜨리면 정상 요청이 400 이 된다. 등록 · 제어 API 모두에 걸린다.
 */
@Configuration
public class StrictJsonConfig {

    @Bean
    JsonMapperBuilderCustomizer strictRequestBody() {
        return builder -> builder
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .withCoercionConfig(LogicalType.Integer, config -> config
                        .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail))
                .withCoercionConfig(LogicalType.Float, config -> config
                        .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail))
                .withCoercionConfig(LogicalType.Textual, config -> config
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
    }
}
