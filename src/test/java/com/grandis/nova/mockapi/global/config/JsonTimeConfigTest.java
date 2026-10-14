package com.grandis.nova.mockapi.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실제 앱 컨텍스트의 JSON 변환기가 시각을 밀리초 세 자리로 쓰는지 본다.
 *
 * <p>등록 시각은 {@code Instant.now()} 라 밀리초가 0 인 경우를 API 로는 고를 수 없다. 변환기에 직접 넣는다.
 */
@SpringBootTest
class JsonTimeConfigTest {

    @Autowired
    private JsonMapper jsonMapper;

    @ParameterizedTest
    @CsvSource({
            "2026-09-16T10:00:03Z,       2026-09-16T10:00:03.000Z",  // 기본값이면 소수부가 빠진다
            "2026-09-16T10:00:03.400Z,   2026-09-16T10:00:03.400Z",
            "2026-09-16T10:00:03.412Z,   2026-09-16T10:00:03.412Z",
            "2026-09-16T10:00:03.412999Z, 2026-09-16T10:00:03.412Z"  // 밀리초까지만
    })
    @DisplayName("응답 시각은 밀리초가 0 이어도 세 자리로 쓴다")
    void writesThreeDigitMillis(String value, String expected) {
        assertThat(jsonMapper.writeValueAsString(Instant.parse(value))).isEqualTo("\"" + expected + "\"");
    }
}
