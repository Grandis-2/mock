package com.grandis.nova.mockapi.load;

import static org.assertj.core.api.Assertions.assertThat;

import com.grandis.nova.mockapi.global.chaos.LatencyTail;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 부하 판정이 관측값에서 빼는 "주입한 지연" 의 계산. 여기가 틀리면 Mock 이 멀쩡해도 오버헤드가
 * 부풀어 판정이 실패하거나, 반대로 느린 Mock 이 통과한다.
 */
class InjectedLatencyTest {

    @Test
    @DisplayName("평균 500 · 지터 0.4 — 300~700 균등이라 p95 680 · p99 696")
    void uniformPercentiles() {
        var latency = new InjectedLatency(500, 0.4);

        assertThat(latency.percentileMs(0)).isEqualTo(300);
        assertThat(latency.percentileMs(50)).isEqualTo(500);
        assertThat(latency.percentileMs(95)).isEqualTo(680);
        assertThat(latency.percentileMs(99)).isEqualTo(696);
        assertThat(latency.percentileMs(100)).isEqualTo(700);
    }

    /** 평균화 이전 Mock 은 지터를 알려주지 않는다. 그때도 지금처럼 "관측 − 설정값" 이어야 한다. */
    @Test
    @DisplayName("지터 0 이면 어느 백분위든 설정값 그대로다")
    void zeroJitterIsMean() {
        var latency = new InjectedLatency(2000, 0.0);

        assertThat(latency.percentileMs(95)).isEqualTo(2000);
        assertThat(latency.percentileMs(99)).isEqualTo(2000);
    }

    @Test
    @DisplayName("지연 0 이면 0 이다 — timeout 시나리오")
    void zeroLatency() {
        assertThat(new InjectedLatency(0, 0.4).percentileMs(95)).isZero();
    }

    /** 지터가 켜져 있어도 지연 0 에 "평균 0ms · 0 ~ 0ms 균등" 이라 적으면 흔드는 것처럼 읽힌다. */
    @Test
    @DisplayName("지연 0 이면 보고서에 '지연 없음' 으로 적는다")
    void describesNoLatency() {
        assertThat(new InjectedLatency(0, 0.4).describe()).isEqualTo("지연 없음");
        assertThat(new InjectedLatency(500, 0.4).describe()).startsWith("평균 500ms · 300 ~ 700ms");
    }

    /**
     * 꼬리가 있으면 몸통은 Mock 과 같은 식으로 낮춘 평균에서 흔든다. 여기서 따로 계산하면 사전 검사가 Mock 과 어긋난다.
     * 2% · 2~4초면 몸통 평균 ≈ 449 → 269 ~ 629.
     */
    @Test
    @DisplayName("꼬리가 있으면 몸통은 보정된 평균으로, 최대는 꼬리 상한까지")
    void tailShiftsBodyAndMax() {
        var latency = new InjectedLatency(500, 0.4, new LatencyTail(0.02, 2000, 4000));

        assertThat(latency.bodyMeanMs()).isEqualTo(new LatencyTail(0.02, 2000, 4000).bodyMeanMs(500));
        assertThat(latency.percentileMs(0)).isEqualTo(269);
        assertThat(latency.bodyMaxMs()).isEqualTo(629);
        assertThat(latency.maxMs()).isEqualTo(4000);
        assertThat(latency.describe()).contains("몸통 평균 449ms").contains("꼬리 2.0% 2000 ~ 4000ms");
    }
}
