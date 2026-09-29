package com.grandis.nova.mockapi.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 설정 파일의 범위 검증. 벗어나면 Mock 이 <b>기동하지 않아야</b> 한다.
 *
 * <p>지연 지터는 설정 API 로 바꿀 수 없어 설정 파일이 유일한 입구다. 여기서 막지 않으면 틀린 값으로
 * 조용히 돈다 — 지터 1.5 면 평균이 500 이 아니라 625 가 된다.
 *
 * <p>지연 · 실패율은 설정 API 와 같은 범위다. 5% 를 {@code failure-rate: 5} 로 잘못 적으면 등록이
 * 전부 실패한 채로 뜬다.
 *
 * <p>앱 전체를 띄우지 않고 설정 바인딩만 본다. 기동 실패를 보려면 컨텍스트를 매번 새로 만들어야
 * 하는데, 전체 컨텍스트로 하면 DB 까지 붙어 느리다.
 */
class MockPropertiesValidationTest {

    @EnableConfigurationProperties(MockProperties.class)
    static class PropertiesOnly {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesOnly.class);

    @Test
    @DisplayName("지터를 주지 않으면 0.4 다")
    void defaultJitter() {
        runner.run(context -> assertThat(context.getBean(MockProperties.class).latencyJitter())
                .isEqualTo(0.4));
    }

    @Test
    @DisplayName("지터 0 과 1 은 받는다 — 경계")
    void acceptsBounds() {
        runner.withPropertyValues("mock.latency-jitter=0.0").run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues("mock.latency-jitter=1.0").run(context -> assertThat(context).hasNotFailed());
    }

    /** 1 을 넘으면 하한만 0 에서 잘려 평균이 올라간다. 기동을 막아야 한다. */
    @Test
    @DisplayName("지터가 1 을 넘으면 기동하지 않는다")
    void rejectsAboveOne() {
        runner.withPropertyValues("mock.latency-jitter=1.5")
                .run(context -> assertThat(context).getFailure()
                        .rootCause().hasMessageContaining("latencyJitter"));
    }

    @Test
    @DisplayName("지터가 음수면 기동하지 않는다")
    void rejectsNegative() {
        runner.withPropertyValues("mock.latency-jitter=-0.1")
                .run(context -> assertThat(context).getFailure()
                        .rootCause().hasMessageContaining("latencyJitter"));
    }

    /** 유지 시간 0 은 "붙잡지 않고 바로 끊는다" 로 시험이 쓰는 값이다. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "mock.register-latency-ms=0", "mock.register-latency-ms=60000",
            "mock.failure-rate=0.0", "mock.failure-rate=1.0",
            "mock.timeout-hold-ms=0"})
    @DisplayName("지연 · 실패율 · 유지 시간의 경계는 받는다")
    void acceptsRangeBounds(String property) {
        runner.withPropertyValues(property).run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "mock.failure-rate=5,           failureRate",
            "mock.failure-rate=-0.1,        failureRate",
            "mock.register-latency-ms=-1,   registerLatencyMs",
            "mock.register-latency-ms=60001, registerLatencyMs",
            "mock.timeout-hold-ms=-1,       timeoutHoldMs"})
    @DisplayName("범위를 벗어나면 기동하지 않는다 — 설정 API 와 같은 범위")
    void rejectsOutOfRange(String property, String field) {
        runner.withPropertyValues(property)
                .run(context -> assertThat(context).getFailure()
                        .rootCause().hasMessageContaining(field));
    }
}
