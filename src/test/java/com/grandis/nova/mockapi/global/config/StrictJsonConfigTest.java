package com.grandis.nova.mockapi.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grandis.nova.mockapi.global.chaos.FailureMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실제 앱 컨텍스트의 JSON 변환기가 계약과 다른 본문을 거절하는지 본다.
 *
 * <p>슬라이스 시험(@WebMvcTest)은 이 설정을 스캔하지 않아서 직접 넣어줘야 한다. 거기서 통과해도
 * 운영에서 이 설정이 실제로 잡히는지는 알 수 없으므로, 전체 컨텍스트로 한 번 확인한다.
 */
@SpringBootTest
class StrictJsonConfigTest {

    @Autowired
    private JsonMapper jsonMapper;

    record Sample(int known) {
    }

    /** 등록 · 설정 요청과 같은 타입들. */
    record Typed(Long id, Double rate, String sku, FailureMode mode) {
    }

    @Test
    @DisplayName("앱의 JSON 변환기는 모르는 필드를 거절한다")
    void rejectsUnknownProperties() {
        assertThatThrownBy(() -> jsonMapper.readValue("{\"known\":1,\"unknown\":2}", Sample.class))
                .isInstanceOf(UnrecognizedPropertyException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"id\":\"1001\"}",            // 문자열 → 정수
            "{\"id\":1.9}",                 // 실수 → 정수 (소수점 버림)
            "{\"id\":\"\"}",                // 빈 문자열 → null
            "{\"rate\":\"0.5\"}",           // 문자열 → 실수
            "{\"sku\":123}",                // 정수 → 문자열
            "{\"sku\":true}",               // 참거짓 → 문자열
            "{\"mode\":1}",                 // 숫자 → 열거형 순번
            "{\"id\":1001,\"id\":1002}"     // 같은 필드 두 번 (마지막 값)
    })
    @DisplayName("타입을 바꿔 받거나 같은 필드의 마지막 값을 쓰지 않는다")
    void rejectsCoercionAndDuplicates(String json) {
        assertThatThrownBy(() -> jsonMapper.readValue(json, Typed.class))
                .isInstanceOf(JacksonException.class);
    }

    /** 실패율 0 · 1 은 정수로 쓰는 게 자연스럽다. 값이 바뀌지 않으니 받는다. */
    @Test
    @DisplayName("정수 → 실수는 받는다")
    void acceptsIntegerAsFloat() {
        assertThat(jsonMapper.readValue("{\"rate\":1}", Typed.class).rate()).isEqualTo(1.0);
    }
}
