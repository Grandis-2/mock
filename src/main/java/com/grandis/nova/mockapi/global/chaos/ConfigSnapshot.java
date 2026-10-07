package com.grandis.nova.mockapi.global.chaos;

import java.util.List;

/**
 * 등록 시도 하나가 시작할 때 고정하는 설정값.
 *
 * <p>처리 도중 설정이 바뀌어도 그 시도는 시작 시점의 값으로 끝난다.
 * {@code configVersion} 은 응답 헤더 {@code X-Mock-Config-Version} 으로 나간다.
 *
 * @param latencyTail    지연의 느린 꼬리. 없으면 {@link LatencyTail#NONE}
 * @param mixedResponses {@link FailureMode#MIXED} 에서 섞을 응답 종류. MIXED 가 아니면 비어 있다. MIXED 인데 비어
 *                       있으면 전부로 채운다 — "생략하면 전부" 를 여기서 한 번만 정해, 꺼내 쓰는 쪽이 빈 목록을 만나지 않게 한다
 */
public record ConfigSnapshot(
        int registerLatencyMs,
        double failureRate,
        FailureMode failureMode,
        int configVersion,
        LatencyTail latencyTail,
        List<MixedResponse> mixedResponses
) {

    public ConfigSnapshot {
        if (latencyTail == null) {
            latencyTail = LatencyTail.NONE;
        }
        if (failureMode != FailureMode.MIXED) {
            mixedResponses = List.of();
        } else if (mixedResponses == null || mixedResponses.isEmpty()) {
            mixedResponses = List.of(MixedResponse.values());
        } else {
            mixedResponses = List.copyOf(mixedResponses);
        }
    }

    /** 섞기 없는 설정. 섞기를 들이기 전부터 쓰던 모양이다. MIXED 면 전부를 섞는다. */
    public ConfigSnapshot(int registerLatencyMs, double failureRate, FailureMode failureMode, int configVersion,
                          LatencyTail latencyTail) {
        this(registerLatencyMs, failureRate, failureMode, configVersion, latencyTail, List.of());
    }

    /** 꼬리 없는 설정. 꼬리를 들이기 전부터 쓰던 모양이라 등록 파트 · 시험이 그대로 쓴다. */
    public ConfigSnapshot(int registerLatencyMs, double failureRate, FailureMode failureMode, int configVersion) {
        this(registerLatencyMs, failureRate, failureMode, configVersion, LatencyTail.NONE);
    }
}
