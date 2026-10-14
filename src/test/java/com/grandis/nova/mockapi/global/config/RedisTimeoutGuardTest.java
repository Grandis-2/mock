package com.grandis.nova.mockapi.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grandis.nova.mockapi.global.chaos.FailureMode;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * redis 모드의 Redis 시간 초과 검사. 스프링 없이 본다 — 기동 실패를 앱으로 띄워 보는 것보다 빠르고, 경계를 숫자로 고정할 수 있다.
 * redis 모드로 실제로 뜨는지는 {@code RedisSharedStateWiringTest} 가 권장값으로 본다.
 */
class RedisTimeoutGuardTest {

    private static final long WORKER_READ_TIMEOUT_MS = 5000;

    @Test
    @DisplayName("권장값(명령 500ms · 연결 1s)이면 통과한다")
    void recommendedPasses() {
        assertThatCode(() -> RedisTimeoutGuard.check("t", Duration.ofMillis(500), WORKER_READ_TIMEOUT_MS))
                .doesNotThrowAnyException();
        assertThatCode(() -> RedisTimeoutGuard.check("t", Duration.ofSeconds(1), WORKER_READ_TIMEOUT_MS))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("정하지 않으면 뜨지 않는다 — Lettuce 기본 60초가 조용히 적용되는 것을 막는다")
    void missingFails() {
        assertThatThrownBy(() -> RedisTimeoutGuard.check("spring.data.redis.timeout", null, WORKER_READ_TIMEOUT_MS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.data.redis.timeout")
                .hasMessageContaining("받은 값: null");
    }

    /**
     * 두 번 기다린 시간이 워커 읽기 타임아웃의 절반을 넘으면 거절한다. 나머지 절반은 지연 · 처리 몫이다 — 2499ms 면 두 번에
     * 4998ms 라 최소 지연 300ms 만 더해도 5초를 넘는다(리뷰 지적).
     */
    @Test
    @DisplayName("두 번 기다린 시간이 워커 읽기 타임아웃의 절반을 넘으면 뜨지 않는다 — 5초면 1250ms 까지, 1251ms 부터 거절")
    void tooLongFails() {
        assertThatCode(() -> RedisTimeoutGuard.check("t", Duration.ofMillis(1250), WORKER_READ_TIMEOUT_MS))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> RedisTimeoutGuard.check("t", Duration.ofMillis(1251), WORKER_READ_TIMEOUT_MS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("절반");
        assertThatThrownBy(() -> RedisTimeoutGuard.check("t", Duration.ofMillis(2499), WORKER_READ_TIMEOUT_MS))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> RedisTimeoutGuard.check("t", Duration.ofSeconds(60), WORKER_READ_TIMEOUT_MS))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("0 이하는 뜨지 않는다")
    void zeroOrNegativeFails() {
        assertThatThrownBy(() -> RedisTimeoutGuard.check("t", Duration.ZERO, WORKER_READ_TIMEOUT_MS))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> RedisTimeoutGuard.check("t", Duration.ofMillis(-1), WORKER_READ_TIMEOUT_MS))
                .isInstanceOf(IllegalStateException.class);
    }

    /** 실제 설정 이름으로 묶이는지 본다 — 이름이 틀리면 값을 줘도 null 로 읽혀 늘 거절되거나, 검사가 아예 안 붙는다. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({DataRedisProperties.class, MockProperties.class})
    @Import(RedisTimeoutGuard.class)
    static class GuardOnly {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(GuardOnly.class);

    @Test
    @DisplayName("redis 모드에 시간 초과가 없으면 문맥이 뜨지 않는다 · 두 줄을 주면 뜬다 · memory 모드는 검사하지 않는다")
    void wiredByPropertyNames() {
        runner.withPropertyValues("mock.shared-state=redis")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("spring.data.redis.timeout"));
        runner.withPropertyValues("mock.shared-state=redis",
                        "spring.data.redis.timeout=500ms", "spring.data.redis.connect-timeout=1s")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(RedisTimeoutGuard.class));
        runner.withPropertyValues("mock.shared-state=memory")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(RedisTimeoutGuard.class));
    }

    @Test
    @DisplayName("명령 · 연결 시간 초과를 둘 다 본다 — 연결만 빠져도 뜨지 않는다")
    void checksBoth() {
        DataRedisProperties redis = new DataRedisProperties();
        redis.setTimeout(Duration.ofMillis(500));
        MockProperties mock = new MockProperties(500, 0.4, 0.05, FailureMode.HTTP_5XX, 7000, WORKER_READ_TIMEOUT_MS,
                SharedState.REDIS);

        assertThatThrownBy(() -> new RedisTimeoutGuard(redis, mock))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.data.redis.connect-timeout");

        redis.setConnectTimeout(Duration.ofSeconds(1));
        assertThatCode(() -> new RedisTimeoutGuard(redis, mock)).doesNotThrowAnyException();
    }
}
