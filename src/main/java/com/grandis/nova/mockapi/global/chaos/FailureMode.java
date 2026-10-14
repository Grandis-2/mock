package com.grandis.nova.mockapi.global.chaos;

/**
 * 실패를 주입할 때 어떤 모양으로 실패할지. 실패할지는 실패율(확률)이 정하고, 걸린 뒤의 모양은 이 값이 정한다.
 *
 * <p>모두 커밋 전에 발생하므로 아무것도 저장되지 않는다.
 */
public enum FailureMode {

    /** 즉시 500 UPSTREAM_UNAVAILABLE. 기본값이다 — 과제의 "5% 실패" · 부하 판정 · 시연이 이 모양에 기댄다. */
    HTTP_5XX,

    /**
     * 응답하지 않은 채 연결을 유지하다 {@code mock.timeout-hold-ms} 뒤에 끊는다.
     * 워커 HTTP 읽기 타임아웃보다 길어야 워커가 타임아웃을 겪는다.
     */
    TIMEOUT,

    /**
     * 걸릴 때마다 {@link MixedResponse} 중 하나를 같은 확률로 고른다. 섞을 종류는 설정의 {@code mixedResponses} 로 정하고,
     * 생략하면 전부다. 하나만 고르면 그 응답만 나온다.
     *
     * <p>{@link #TIMEOUT} 은 섞지 않는다 — 연결을 붙잡아 무겁고, 따로 고를 수 있다.
     */
    MIXED
}
