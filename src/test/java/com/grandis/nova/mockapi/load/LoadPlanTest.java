package com.grandis.nova.mockapi.load;

import static org.assertj.core.api.Assertions.assertThat;

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
}
