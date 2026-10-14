package com.grandis.nova.mockapi.global.config;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * 코드와 함께 배포하는 설정 기본값. 가장 낮은 우선순위라 yaml · 환경 변수가 같은 키를 주면 그 값이 이긴다.
 *
 * <p>헬스 체크 설정만 둔다. 운영 설정은 각자 복사해 쓰는 {@code application.yml} 과 인프라가 넣는 값에만 있어서,
 * 거기 빠지면 헬스 경로가 조용히 사라진다. 관리 포트는 인프라의 헬스 체크와 맞춘 고정값이라 여기 둔다(be 서비스들과 같은 방식).
 */
class MockDefaults implements EnvironmentPostProcessor, Ordered {

    static final String SOURCE_NAME = "mockDefaults";

    /** 관리 포트. 인프라 service.yml 의 management_port · health.port 와 같아야 한다. */
    static final String MANAGEMENT_PORT = "9081";

    static final Map<String, Object> DEFAULTS = Map.of(
            // 헬스는 서비스 포트(8081, 워커가 요청을 보내는 곳)와 나눈 관리 포트로만 내놓는다
            "management.server.port", MANAGEMENT_PORT,
            "management.endpoints.web.exposure.include", "health",
            // 헬스 체크가 보는 /actuator/health/readiness · liveness. Boot 4.1 은 기본으로 켜지만 버전이 바뀌어도 경로가 사라지지 않게 고정한다
            "management.endpoint.health.probes.enabled", "true",
            // readiness 는 앱의 준비 상태만 본다. DB 를 넣으면 공용 DB 장애 한 번에 태스크가 비정상이 돼 ECS 가 갈아 끼운다 —
            // 갈아 끼워도 DB 는 그대로라 Mock 만 재기동을 반복하고, 실행 중에 바꾼 설정(memory)도 잃는다
            "management.endpoint.health.group.readiness.include", "readinessState");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, withRedisHealth(environment)));
        requireSeparateManagementPort(environment);
    }

    /**
     * Redis 는 shared-state=redis 일 때만 쓴다. 기본(memory)에서도 의존성이 있어 Redis 헬스가 붙는데, 붙을 Redis 가 없어
     * 전체 헬스(/actuator/health)가 늘 DOWN 으로 보인다. 쓰지 않을 때는 뺀다.
     */
    private static Map<String, Object> withRedisHealth(ConfigurableEnvironment environment) {
        SharedState state = Binder.get(environment).bind("mock.shared-state", SharedState.class).orElse(SharedState.MEMORY);
        if (state == SharedState.REDIS) {
            return DEFAULTS;
        }
        Map<String, Object> defaults = new HashMap<>(DEFAULTS);
        defaults.put("management.health.redis.enabled", "false");
        return Map.copyOf(defaults);
    }

    /**
     * 관리 포트가 서비스 포트와 같거나 꺼져 있으면 기동을 막는다. 같으면 헬스가 워커가 부르는 서비스 포트로 나가고, 헬스 체크가 보는
     * 관리 포트는 아무도 듣지 않아 태스크가 비정상으로 갈아 끼워진다. 배포 설정이 management.server.port 를 덮는 실수를 기동에서 드러낸다.
     */
    static void requireSeparateManagementPort(ConfigurableEnvironment environment) {
        ManagementPortType type = ManagementPortType.get(environment);
        if (type != ManagementPortType.DIFFERENT) {
            throw new IllegalStateException("관리 포트를 서비스 포트와 다른 포트로 켜야 한다(헬스 체크는 관리 포트로만 — 같은 포트 SAME · 꺼짐 DISABLED 는 거부): "
                    + type + ", server.port=" + environment.getProperty("server.port")
                    + ", management.server.port=" + environment.getProperty("management.server.port"));
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
