package com.grandis.nova.mockapi.load;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 시나리오가 응답 타임아웃에 걸치는지. 걸치면 Mock 이 기준대로 빨라도 꼬리의 요청이 결과 불명이 되어
 * 분류 판정(결과 불명 허용 0)이 실패한다 — Mock 의 결과가 아니라 시나리오 설계의 결과다.
 */
class LoadPlanTest {

    private static final String URL = "http://localhost:8081";

    /** 지금 `latency` 시나리오다. 최대 2100 + 허용 400 = 2500 < 3000. */
    @Test
    @DisplayName("평균 1500 · 지터 0.4 는 분류 판정(3초)에 들어간다")
    void latencyScenarioFits() {
        assertThat(LoadPlan.classify(URL, 5_000).leavesRoomFor(new InjectedLatency(1500, 0.4))).isTrue();
    }

    /** 바꾸기 전 `latency` 시나리오. 최대 2800 + 허용 400 = 3200 > 3000. */
    @Test
    @DisplayName("평균 2000 · 지터 0.4 는 분류 판정(3초)에 걸친다")
    void twoSecondsWithJitterOverflows() {
        assertThat(LoadPlan.classify(URL, 5_000).leavesRoomFor(new InjectedLatency(2000, 0.4))).isFalse();
    }

    /** 평균화 이전 Mock(고정 2000) 은 들어갔다. 이번 변경이 그 판정을 깨지 않는지. */
    @Test
    @DisplayName("고정 2000 은 분류 판정(3초)에 들어간다 — 이전 판정과 같은 조건")
    void fixedTwoSecondsFits() {
        assertThat(LoadPlan.classify(URL, 5_000).leavesRoomFor(new InjectedLatency(2000, 0.0))).isTrue();
    }

    @Test
    @DisplayName("지연 판정(10초)은 평균 2000 · 지터 0.4 도 들어간다")
    void latencyPassHasRoom() {
        assertThat(LoadPlan.latency(URL, 5_000).leavesRoomFor(new InjectedLatency(2000, 0.4))).isTrue();
    }
}
