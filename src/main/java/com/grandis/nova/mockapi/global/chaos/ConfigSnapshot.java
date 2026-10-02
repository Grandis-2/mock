package com.grandis.nova.mockapi.global.chaos;

/**
 * 등록 시도 하나가 시작할 때 고정하는 설정값.
 *
 * <p>처리 도중 설정이 바뀌어도 그 시도는 시작 시점의 값으로 끝난다.
 * {@code configVersion} 은 응답 헤더 {@code X-Mock-Config-Version} 으로 나간다.
 *
 * @param latencyTail 지연의 느린 꼬리. 없으면 {@link LatencyTail#NONE}
 */
public record ConfigSnapshot(
        int registerLatencyMs,
        double failureRate,
        FailureMode failureMode,
        int configVersion,
        LatencyTail latencyTail
) {

    public ConfigSnapshot {
        if (latencyTail == null) {
            latencyTail = LatencyTail.NONE;
        }
    }

    /** 꼬리 없는 설정. 꼬리를 들이기 전부터 쓰던 모양이라 등록 파트 · 시험이 그대로 쓴다. */
    public ConfigSnapshot(int registerLatencyMs, double failureRate, FailureMode failureMode, int configVersion) {
        this(registerLatencyMs, failureRate, failureMode, configVersion, LatencyTail.NONE);
    }
}
