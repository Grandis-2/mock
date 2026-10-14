package com.grandis.nova.mockapi.global.config;

import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.stereotype.Component;

/**
 * 여러 대({@code mock.shared-state=redis})로 띄울 때 Redis 시간 초과가 정해져 있지 않거나 너무 길면 Mock 을 띄우지 않는다.
 *
 * <p>redis 모드는 새 등록마다 결함을 Redis 에서 두 번 꺼내 본다 — 커밋 전 느린 성공 확인, 커밋 뒤 응답 유실 확인. Redis 가 멈추면
 * 그 시간 초과만큼 기다린 뒤에야 "결함 없음" 으로 넘어간다. 정하지 않으면 Lettuce 기본(명령 60초 · 연결 10초)이라, 그동안 모든
 * 등록이 멈추고 커밋된 등록이 응답 전에 묶여 워커는 <b>주입하지 않은 결과 불명</b>을 받는다 — 시험 결과가 오염된다.
 *
 * <p>값은 각자 복사해 쓰는 {@code application.yml} 과 인프라의 {@code mock/application.yml} 에만 있다(인프라는 host · port · ssl 만
 * 넣는다). 빠져도 오류 없이 기본값으로 돌아서 {@link VirtualThreadGuard} 와 같은 이유로 기동할 때 확인한다.
 *
 * <p>상한은 워커 읽기 타임아웃({@link MockProperties#workerReadTimeoutMs()})에서 나온다 — 두 번 다 시간 초과돼도 그 <b>절반</b>
 * 안이어야 한다(나머지 절반은 지연 · 처리 몫, {@link #WORKER_TIMEOUT_SHARE_DIVISOR}). 권장값(명령 500ms · 연결 1s)이면 기본 워커
 * 5초에서 1초 · 2초로 2.5초 안이다.
 */
@Component
@ConditionalOnProperty(prefix = "mock", name = "shared-state", havingValue = "redis")
class RedisTimeoutGuard {

    /** 새 등록 하나가 결함을 Redis 에서 꺼내 보는 최대 횟수(느린 성공 · 응답 유실). */
    static final int LOOKUPS_PER_REGISTRATION = 2;

    /**
     * 워커 읽기 타임아웃 가운데 Redis 몫. 나머지 절반은 주입한 지연과 처리(잠금 · 저장 · 응답) 몫이다 — Redis 두 번만 맞춰 두면
     * 기본 지연(300~700ms)만 더해도 워커가 먼저 포기해 주입하지 않은 결과 불명이 난다.
     *
     * <p>지금 설정된 지연값으로 계산하지 않는다. 지연은 설정 API 로 실행 중에 바뀌고(0~60초), 일부러 타임아웃을 넘기는 시나리오
     * (꼬리)도 있어 기동할 때 한 번 본 값이 근거가 되지 못한다. 그래서 고정 비율로 여유를 남긴다.
     */
    static final int WORKER_TIMEOUT_SHARE_DIVISOR = 2;

    RedisTimeoutGuard(DataRedisProperties redis, MockProperties mock) {
        check("spring.data.redis.timeout", redis.getTimeout(), mock.workerReadTimeoutMs());
        check("spring.data.redis.connect-timeout", redis.getConnectTimeout(), mock.workerReadTimeoutMs());
    }

    /** @throws IllegalStateException 없거나 0 이하이거나, 두 번 기다리면 워커 읽기 타임아웃의 절반을 넘을 때 */
    static void check(String name, Duration value, long workerReadTimeoutMs) {
        if (value == null || value.isZero() || value.isNegative()
                || value.multipliedBy(LOOKUPS_PER_REGISTRATION * WORKER_TIMEOUT_SHARE_DIVISOR)
                        .compareTo(Duration.ofMillis(workerReadTimeoutMs)) > 0) {
            throw new IllegalStateException(("mock.shared-state=redis 면 %s 를 정해야 합니다(권장: timeout 500ms · connect-timeout 1s,"
                    + " application.yml.example 참고). 등록 하나가 결함을 Redis 에서 두 번 보므로 그 두 배가 워커 읽기 타임아웃"
                    + "(%dms)의 절반 이하여야 합니다 — 나머지 절반은 지연 · 처리 몫. 빠지면 Lettuce 기본(60초 · 10초)이라 Redis 가"
                    + " 멈출 때 등록이 그만큼 묶입니다. 받은 값: %s").formatted(name, workerReadTimeoutMs, value));
        }
    }
}
