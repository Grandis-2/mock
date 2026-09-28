package com.grandis.nova.mockapi.admin.dto;

import com.grandis.nova.mockapi.global.chaos.ConfigSnapshot;
import com.grandis.nova.mockapi.global.chaos.FailureMode;
import com.grandis.nova.mockapi.global.chaos.MockConfigStore;
import java.time.Instant;

/**
 * 설정 조회·변경의 응답. 둘이 같은 형식이다.
 *
 * <p>요청값이 아니라 <b>실제 적용된 값</b>을 담는다. 적용 실패를 저장 성공으로 표시하지
 * 않기 위해서다(요구사항 4.4).
 *
 * <p>안쪽 모델({@link ConfigSnapshot})을 그대로 내보내지 않는다. 그쪽은 A 파트와의 계약이라
 * 응답에 필드를 늘릴 때마다 계약을 건드리게 된다. {@code latencyJitter} 가 그 예다 — 응답에는
 * 있지만 스냅샷에는 없다.
 *
 * @param registerLatencyMs 지연의 <b>평균</b>. 실제 대기는 {@code latencyJitter} 만큼 흔들린다
 * @param latencyJitter     지연을 흔드는 폭. 실제 대기는 {@code 평균 × (1 ∓ 이 값)} 의 균등분포다.
 *                          설정 파일로만 정하고 이 API 로는 바꾸지 않는다. 부하 하네스가 이 값을 읽어
 *                          주입한 지연의 백분위를 계산하고, 요구사항 5.4 대로 적용한 설정을 기록한다
 */
public record ConfigResponse(
        int registerLatencyMs,
        double latencyJitter,
        double failureRate,
        FailureMode failureMode,
        int configVersion,
        Instant appliedAt
) {

    /** 중첩된 Applied 를 평평한 응답 모양으로 편다. */
    public static ConfigResponse from(MockConfigStore.Applied applied, double latencyJitter) {
        ConfigSnapshot snapshot = applied.snapshot();
        return new ConfigResponse(
                snapshot.registerLatencyMs(),
                latencyJitter,
                snapshot.failureRate(),
                snapshot.failureMode(),
                snapshot.configVersion(),
                applied.appliedAt());
    }
}
