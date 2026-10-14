package com.grandis.nova.mockapi.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

/**
 * 설정 기본값과 관리 포트 검사. 앱을 띄우지 않고 후처리기만 돌린다 — 포트를 바꿔 가며 기동 성패를 보려면 컨텍스트를 매번 새로
 * 만들어야 하는데, 전체 컨텍스트로 하면 DB 까지 붙어 느리다.
 */
class MockDefaultsTest {

    private final MockDefaults defaults = new MockDefaults();

    private MockEnvironment process(MockEnvironment environment) {
        defaults.postProcessEnvironment(environment, new SpringApplication());
        return environment;
    }

    @Test
    @DisplayName("기본값 — 관리 포트 9081 에 health 만 연다. readiness 는 앱 상태만 본다")
    void appliesDefaults() {
        MockEnvironment environment = process(new MockEnvironment().withProperty("server.port", "8081"));

        assertThat(environment.getProperty("management.server.port")).isEqualTo(MockDefaults.MANAGEMENT_PORT);
        assertThat(environment.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health");
        assertThat(environment.getProperty("management.endpoint.health.probes.enabled")).isEqualTo("true");
        assertThat(environment.getProperty("management.endpoint.health.group.readiness.include")).isEqualTo("readinessState");
    }

    @Test
    @DisplayName("설정이 같은 키를 주면 그 값이 이긴다 — 기본값은 가장 낮은 우선순위다")
    void configurationWins() {
        MockEnvironment environment = process(new MockEnvironment()
                .withProperty("server.port", "8081").withProperty("management.server.port", "9999"));

        assertThat(environment.getProperty("management.server.port")).isEqualTo("9999");
    }

    @Test
    @DisplayName("Redis 헬스는 redis 모드에서만 켠다")
    void redisHealthFollowsSharedState() {
        assertThat(process(new MockEnvironment()).getProperty("management.health.redis.enabled")).isEqualTo("false");
        assertThat(process(new MockEnvironment().withProperty("mock.shared-state", "redis"))
                .getProperty("management.health.redis.enabled")).isNull();
    }

    @Test
    @DisplayName("관리 포트가 서비스 포트와 같으면 뜨지 않는다 — 헬스가 워커가 부르는 포트로 나가고 관리 포트는 아무도 듣지 않는다")
    void refusesSamePort() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("server.port", "8081").withProperty("management.server.port", "8081");

        assertThatThrownBy(() -> process(environment))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SAME");
    }

    @Test
    @DisplayName("관리 포트를 끄면(-1) 뜨지 않는다 — 헬스 체크가 볼 곳이 없다")
    void refusesDisabledPort() {
        MockEnvironment environment = new MockEnvironment().withProperty("management.server.port", "-1");

        assertThatThrownBy(() -> process(environment))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("DISABLED");
    }

    @Test
    @DisplayName("시험처럼 관리 포트를 0(빈 포트)으로 두면 다른 포트로 본다")
    void acceptsRandomPort() {
        assertThatCode(() -> process(new MockEnvironment().withProperty("management.server.port", "0")))
                .doesNotThrowAnyException();
    }
}
