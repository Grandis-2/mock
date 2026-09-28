package com.grandis.nova.mockapi.load;

import java.time.Duration;

/**
 * 부하 시험의 조건. 요구사항 8장이 실행 전 합의를 요구하므로 값을 코드에 흩지 않고 여기 모은다.
 *
 * <p>아래 값은 2026-09-28 A 와 합의한 것이다. 다만 두 가지는 <b>잠정</b>이며 패스 B 재측정 뒤에
 * 확정한다 — {@link #maxUnknownRate} 와 오버헤드 기준 두 숫자.
 *
 * @param pass              이 실행이 무엇을 판정하는가. {@link Pass} 참고
 * @param totalRequests     보낼 요청 수. 합의값 5,000
 * @param rampUp            이 시간에 걸쳐 고르게 발사한다. 합의값 10초
 * @param responseTimeout   이 시간 안에 응답이 없으면 결과 불명으로 센다. 패스마다 다르다
 * @param drainTimeout      발사가 끝난 뒤 남은 응답을 기다리는 한계. 관찰 종료 조건. 합의값 30초
 * @param maxUnknownRate    {@code HTTP_5XX} 모드에서 허용하는 결과 불명 비율
 * @param maxP95Overhead    p95 에서 설정 지연을 뺀 값의 허용치
 * @param maxP99Overhead    p99 에서 설정 지연을 뺀 값의 허용치
 * @param maxLaunchLag      계획한 발사 시각보다 늦어도 되는 한계. 넘으면 목표 부하를 만들지 못한 것이다
 */
public record LoadPlan(
        Pass pass,
        String baseUrl,
        int totalRequests,
        Duration rampUp,
        Duration responseTimeout,
        Duration drainTimeout,
        double maxUnknownRate,
        Duration maxP95Overhead,
        Duration maxP99Overhead,
        Duration maxLaunchLag
) {

    /**
     * 판정을 두 번에 나눈 이유 — {@code responseTimeout} 이 두 가지를 동시에 할 수 없다.
     *
     * <ul>
     *   <li>분류를 판정하려면 <b>워커의 읽기 타임아웃과 같아야</b> 한다. 실제 워커가 겪을 분류가
     *       나와야 의미가 있다
     *   <li>지연을 판정하려면 <b>설정 지연보다 충분히 커야</b> 한다. 아니면 느린 요청이 타임아웃으로
     *       빠져 백분위가 잘린 분포에서 나온다
     * </ul>
     *
     * <p>겸하려 하면 어느 한쪽이 왜곡된다. 그래서 같은 하네스를 두 번 돌린다.
     */
    public enum Pass {

        /**
         * 분류 판정. 응답 타임아웃을 <b>워커 읽기 타임아웃</b>에 맞춘다.
         *
         * <p>{@code timeout} 시나리오도 이 패스에서 돌린다. 유지 5초 &gt; 타임아웃 3초라
         * {@code mock.timeout-hold-ms} 를 건드리지 않고 결과 불명이 나온다. 타임아웃을 유지 시간보다
         * 크게 잡으면 붙잡던 연결이 끝나며 나가는 500 을 받아 <b>결과 불명이 일시 실패로 세어진다.</b>
         */
        CLASSIFY,

        /** 지연 판정. 타임아웃을 넉넉히 두어 백분위가 잘리지 않게 한다. 분류는 참고로만 본다. */
        LATENCY
    }

    /**
     * 워커 HTTP 읽기 타임아웃 <b>가정값</b>. 명세 기재값이고 실제 값은 아직 받지 못했다.
     *
     * <p>이 값이 정해지면 여기와 {@code mock.timeout-hold-ms}(= 이 값 + 2초)를 함께 옮기고
     * 패스 A 를 다시 돌린다. 코드는 바뀌지 않는다.
     */
    public static final Duration ASSUMED_WORKER_READ_TIMEOUT = Duration.ofSeconds(3);

    /**
     * <b>관찰 종료 조건은 요청 하나의 응답 타임아웃보다 길어야 한다.</b> 짧으면 아직 자기 타임아웃이
     * 오지 않은 요청이 관찰 창 때문에 잘려 결과 불명으로 세어진다. 측정하려던 것이 아니라 시험
     * 설정이 만들어낸 숫자가 되므로, 조건을 바꿀 때 실수하지 않게 여기서 막는다.
     */
    public LoadPlan {
        if (drainTimeout.compareTo(responseTimeout) <= 0) {
            throw new IllegalArgumentException(
                    "관찰 종료 조건(%s)은 응답 타임아웃(%s)보다 길어야 합니다"
                            .formatted(drainTimeout, responseTimeout));
        }
    }

    /** 분류를 판정하는 실행. 응답 타임아웃을 워커 가정값에 맞춘다. */
    public static LoadPlan classify(String baseUrl, int totalRequests) {
        return agreed(Pass.CLASSIFY, baseUrl, totalRequests, ASSUMED_WORKER_READ_TIMEOUT);
    }

    /** 지연을 판정하는 실행. 설정 지연 2000ms 에 충분한 여유를 두려고 10초로 잡는다. */
    public static LoadPlan latency(String baseUrl, int totalRequests) {
        return agreed(Pass.LATENCY, baseUrl, totalRequests, Duration.ofSeconds(10));
    }

    private static LoadPlan agreed(Pass pass, String baseUrl, int totalRequests,
                                   Duration responseTimeout) {
        return new LoadPlan(
                pass,
                baseUrl,
                totalRequests,
                Duration.ofSeconds(10),
                responseTimeout,
                Duration.ofSeconds(30),
                // HTTP_5XX 모드에서 응답이 없다는 것은 Mock 이 제시간에 못 답했다는 뜻이다.
                // 2026-09-28 판정에서 깨끗한 회차는 전부 0건이었으므로 0 으로 확정했다.
                0.0,
                // 2026-09-28 판정 실측으로 확정. 깨끗한 회차의 최대가 p95 106ms · p99 187ms 라
                // 두 배 남짓 여유를 뒀다.
                //
                // 비율 성분을 뺀 이유 — 오버헤드가 설정 지연에 비례하지 않는다. 설정 500ms 에서
                // 99ms, 2000ms 에서 106ms 로 거의 같았다. HTTP · DB 커밋 · 스케줄링의 고정 비용이라
                // 그렇다. 비례로 두면 지연을 크게 잡을수록 기준이 헐거워진다.
                Duration.ofMillis(250),
                Duration.ofMillis(400),
                // 5,000건을 10초에 고르게 쏘는 것이 전제다. 1초 넘게 밀렸다면 클라이언트나 PC 가
                // 멈칫한 것이고, 그 실행은 목표 부하를 만들지 못했다.
                Duration.ofSeconds(1));
    }

    /** 발사 간격. 요청을 한 번에 쏟지 않고 이 간격으로 고르게 낸다. */
    public Duration launchInterval() {
        return totalRequests <= 1 ? Duration.ZERO : rampUp.dividedBy(totalRequests);
    }

    /**
     * 계획한 대로 발사했는가. 아니면 <b>목표 부하를 만들지 못한 실행</b>이라 성능 판정에서 뺀다.
     *
     * <p>요구사항 8장의 "목표 부하를 만들지 못한 시험은 성능 합격으로 판정하지 않는다" 를 숫자로
     * 옮긴 것이다. 계약 판정(키 대조 · 분류 합)은 이것과 무관하게 그대로 본다 — 부하가 덜 걸렸다고
     * 계약이 깨져도 되는 것은 아니다.
     */
    public boolean targetLoadAchieved(Duration observedLaunchLag) {
        return observedLaunchLag.compareTo(maxLaunchLag) <= 0;
    }
}
