package com.grandis.nova.mockapi.load;

import java.time.Duration;

/**
 * 부하 시험의 조건. <b>전부 합의 대상이며 아직 확정되지 않았다.</b>
 *
 * <p>요구사항 8장은 환경 · 요청 규모 · 발생 구간 · 관찰 종료 조건 · 허용 실패율 · 응답 지연 기준을
 * 미리 합의하지 않은 시험을 성능 합격으로 판정하지 않는다. 그래서 값을 코드에 박지 않고 여기 모아
 * 두고, 실행할 때 넘긴다. 합의가 끝나면 숫자만 바꾸면 된다.
 *
 * <p>아래 기본값은 합의된 값이 아니라 <b>과제 예시(10초 5,000건)에서 가져온 임시값</b>이다.
 * 이 값으로 낸 결과를 성능 합격의 근거로 쓰면 안 된다.
 *
 * @param baseUrl          Mock 주소
 * @param totalRequests    보낼 요청 수. 과제 예시는 5,000
 * @param rampUp           이 시간에 걸쳐 고르게 발사한다. 과제 예시는 10초
 * @param responseTimeout  이 시간 안에 응답이 없으면 결과 불명으로 센다.
 *                         <b>워커의 HTTP 읽기 타임아웃과 같아야</b> 실제 워커가 겪을 분류가 나온다
 * @param drainTimeout     발사가 끝난 뒤 남은 응답을 기다리는 한계. 관찰 종료 조건이다
 * @param maxFailureRate   합격으로 볼 실패율 상한. 판정에만 쓰고 시험 진행에는 영향이 없다
 * @param p95Target        합격으로 볼 p95 응답 지연
 * @param p99Target        합격으로 볼 p99 응답 지연
 */
public record LoadPlan(
        String baseUrl,
        int totalRequests,
        Duration rampUp,
        Duration responseTimeout,
        Duration drainTimeout,
        double maxFailureRate,
        Duration p95Target,
        Duration p99Target
) {

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

    /**
     * 과제 예시 기준의 임시 계획. 합의 전까지 구조를 확인하는 용도다.
     *
     * <p>{@code responseTimeout} 3초는 명세가 워커 읽기 타임아웃으로 가정한 값이다. 실제 값이
     * 정해지면 Mock 의 {@code timeout-hold-ms} 와 함께 바꾼다.
     */
    public static LoadPlan draft(String baseUrl) {
        return new LoadPlan(
                baseUrl,
                5_000,
                Duration.ofSeconds(10),
                Duration.ofSeconds(3),
                Duration.ofSeconds(30),
                0.05,
                Duration.ofMillis(1_000),
                Duration.ofMillis(2_000));
    }

    /** 발사 간격. 요청을 한 번에 쏟지 않고 이 간격으로 고르게 낸다. */
    public Duration launchInterval() {
        return totalRequests <= 1 ? Duration.ZERO : rampUp.dividedBy(totalRequests);
    }
}
