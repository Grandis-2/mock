package com.grandis.nova.mockapi.control.api;

import com.grandis.nova.mockapi.global.chaos.ConfigSnapshot;
import com.grandis.nova.mockapi.global.chaos.FailureMode;
import com.grandis.nova.mockapi.global.chaos.MockConfigStore;
import com.grandis.nova.mockapi.global.config.MockProperties;
import java.time.Instant;

/**
 * 설정 조회·변경의 응답. 둘이 같은 형식이다.
 *
 * <p>요청값이 아니라 <b>실제 적용된 값</b>을 담는다. 적용 실패를 저장 성공으로 표시하지
 * 않기 위해서다(요구사항 4.4).
 *
 * <p>안쪽 모델({@link ConfigSnapshot})을 그대로 내보내지 않는다. 그쪽은 등록 파트와의 계약이라
 * 응답에 필드를 늘릴 때마다 계약을 건드리게 된다. {@code latencyJitter} 가 그 예다 — 응답에는
 * 있지만 스냅샷에는 없다.
 *
 * @param registerLatencyMs   지연의 <b>평균</b>. 실제 대기는 {@code latencyJitter} 만큼 흔들린다
 * @param latencyJitter       지연을 흔드는 폭. 실제 대기는 {@code 평균 × (1 ∓ 이 값)} 의 균등분포다.
 *                            설정 파일로만 정하고 이 API 로는 바꾸지 않는다. 부하 하네스가 이 값을 읽어
 *                            주입한 지연의 백분위를 계산하고, 요구사항 5.4 대로 적용한 설정을 기록한다
 * @param latencyTailRate     지연의 느린 꼬리에 걸릴 확률. 0 이면 꼬리 없음(이때 구간도 0)
 * @param latencyTailMinMs    꼬리 구간 하한
 * @param latencyTailMaxMs    꼬리 구간 상한(포함)
 * @param bodyLatencyMs       꼬리에 걸리지 않은 요청의 평균 — 전체 평균이 {@code registerLatencyMs} 가 되도록 꼬리만큼
 *                            낮춘 값(반올림). 꼬리가 없으면 {@code registerLatencyMs} 와 같다. 읽기 전용이다
 * @param timeoutHoldMs       {@code TIMEOUT} · 결함이 응답 없이 붙잡는 시간. 설정 파일로만 정한다(읽기 전용).
 *                            부하 보고서가 "어떤 조건의 Mock 이었나" 를 남기는 데 쓴다
 * @param workerReadTimeoutMs 워커 읽기 타임아웃으로 적어 둔 값. 설정 파일로만 정한다(읽기 전용)
 */
public record ConfigResponse(
        int registerLatencyMs,
        double latencyJitter,
        double latencyTailRate,
        int latencyTailMinMs,
        int latencyTailMaxMs,
        long bodyLatencyMs,
        double failureRate,
        FailureMode failureMode,
        int configVersion,
        Instant appliedAt,
        long timeoutHoldMs,
        long workerReadTimeoutMs
) {

    /** 중첩된 Applied 를 평평한 응답 모양으로 펴고, 설정 파일로만 정하는 값을 곁들인다. */
    public static ConfigResponse from(MockConfigStore.Applied applied, MockProperties properties) {
        ConfigSnapshot snapshot = applied.snapshot();
        return new ConfigResponse(
                snapshot.registerLatencyMs(),
                properties.latencyJitter(),
                snapshot.latencyTail().rate(),
                snapshot.latencyTail().minMs(),
                snapshot.latencyTail().maxMs(),
                Math.round(snapshot.latencyTail().bodyMeanMs(snapshot.registerLatencyMs())),
                snapshot.failureRate(),
                snapshot.failureMode(),
                snapshot.configVersion(),
                applied.appliedAt(),
                properties.timeoutHoldMs(),
                properties.workerReadTimeoutMs());
    }
}
