package com.grandis.nova.mockapi.load;

import static org.assertj.core.api.Assertions.assertThat;

import com.grandis.nova.mockapi.global.chaos.LatencyTail;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 시나리오가 응답 타임아웃에 걸치는지. 걸치면 Mock 이 기준대로 빨라도 꼬리의 요청이 결과 불명이 되어
 * 분류 판정(결과 불명 허용 0)이 실패한다 — Mock 의 결과가 아니라 시나리오 설계의 결과다.
 */
class LoadPlanTest {

    private static final String URL = "http://localhost:8081";

    /** be 워커 read-timeout 5s 와 같아야 워커가 겪을 결과를 센다(docs/api.md). */
    @Test
    @DisplayName("분류 판정의 응답 타임아웃은 워커 읽기 타임아웃 5초다")
    void classifyMatchesWorkerTimeout() {
        assertThat(LoadPlan.classify(URL, 5_000).responseTimeout()).isEqualTo(Duration.ofSeconds(5));
    }

    /** 지금 `latency` 시나리오다. 최대 2100 + 허용 400 = 2500 < 5000. */
    @Test
    @DisplayName("평균 1500 · 지터 0.4 는 분류 판정(5초)에 들어간다")
    void latencyScenarioFits() {
        assertThat(LoadPlan.classify(URL, 5_000).leavesRoomFor(new InjectedLatency(1500, 0.4))).isTrue();
    }

    /** 3초를 가정하던 때는 걸쳤다(2800 + 400 = 3200 > 3000). 5초에서는 3200 < 5000. */
    @Test
    @DisplayName("평균 2000 · 지터 0.4 는 분류 판정(5초)에 들어간다 — 3초 가정 때는 걸쳤다")
    void twoSecondsWithJitterNowFits() {
        assertThat(LoadPlan.classify(URL, 5_000).leavesRoomFor(new InjectedLatency(2000, 0.4))).isTrue();
    }

    /** 최대 4900 + 허용 400 = 5300 > 5000. 운영 가이드의 "평균 3,500ms 이상" 과 같은 경계다. */
    @Test
    @DisplayName("평균 3500 · 지터 0.4 는 분류 판정(5초)에 걸친다")
    void threeAndHalfSecondsOverflows() {
        assertThat(LoadPlan.classify(URL, 5_000).leavesRoomFor(new InjectedLatency(3500, 0.4))).isFalse();
    }

    @Test
    @DisplayName("지연 판정(10초)은 평균 3500 · 지터 0.4 도 들어간다")
    void latencyPassHasRoom() {
        assertThat(LoadPlan.latency(URL, 5_000).leavesRoomFor(new InjectedLatency(3500, 0.4))).isTrue();
    }

    // ---------------------------------------------------------------- 지연 꼬리 (NV-260)

    private static final InjectedLatency TAIL = new InjectedLatency(500, 0.4, new LatencyTail(0.02, 2000, 4000));
    private static final InjectedLatency OVER_TIMEOUT =
            new InjectedLatency(500, 0.4, new LatencyTail(0.005, 5500, 6500));

    /** `tail` 시나리오. 꼬리 상한 4000 + 허용 400 = 4400 < 5000 — 결과 불명이 0 이어야 하는 시나리오다. */
    @Test
    @DisplayName("tail(2% · 2~4초)은 분류 판정(5초)에 들어간다")
    void tailFits() {
        assertThat(TAIL.maxMs()).isEqualTo(4000);
        assertThat(LoadPlan.classify(URL, 5_000).leavesRoomFor(TAIL)).isTrue();
    }

    /**
     * `tail-over-timeout` 시나리오. 꼬리는 일부러 넘기므로 몸통만 본다 — 몸통 최대 ≈ 661(보정 평균 472 × 1.4)
     * + 400 < 5000. 꼬리 하한 5500 > 5000 이라 꼬리 요청은 전부 결과 불명이다.
     */
    @Test
    @DisplayName("tail-over-timeout(0.5% · 5.5~6.5초)은 몸통만 보고 들어간다")
    void overTimeoutTailChecksBodyOnly() {
        LoadPlan classify = LoadPlan.classify(URL, 5_000);

        assertThat(OVER_TIMEOUT.tailOverTimeout(classify.responseTimeout())).isTrue();
        assertThat(classify.leavesRoomFor(OVER_TIMEOUT)).isTrue();
        assertThat(classify.roomDetail(OVER_TIMEOUT)).startsWith("몸통 최대 ").contains("일부러 넘긴다");
    }

    /**
     * 꼬리가 타임아웃에 걸치면(4.5~6초) 일부만 결과 불명이 되어 꼬리 탓인지 Mock 탓인지 가를 수 없다. 넘기는 꼬리로
     * 보지 않고 전체 최대(6000 + 400 > 5000)로 보므로 쏘기 전에 걸린다.
     */
    @Test
    @DisplayName("타임아웃에 걸치는 꼬리는 사전 검사에서 걸린다")
    void straddlingTailRejected() {
        var straddling = new InjectedLatency(500, 0.4, new LatencyTail(0.005, 4500, 6000));
        LoadPlan classify = LoadPlan.classify(URL, 5_000);

        assertThat(straddling.tailOverTimeout(classify.responseTimeout())).isFalse();
        assertThat(classify.leavesRoomFor(straddling)).isFalse();
    }

    /** 지연 판정(10초)에서는 5.5~6.5초 꼬리도 제시간이라 "넘기는 꼬리" 가 아니다. 판정은 classify 만 쓴다. */
    @Test
    @DisplayName("지연 판정(10초)에서는 tail-over-timeout 의 꼬리도 넘지 않는다")
    void overTimeoutTailIsNotOverInLatencyPass() {
        LoadPlan latency = LoadPlan.latency(URL, 5_000);

        assertThat(OVER_TIMEOUT.tailOverTimeout(latency.responseTimeout())).isFalse();
        assertThat(latency.leavesRoomFor(OVER_TIMEOUT)).isTrue();
    }
}
