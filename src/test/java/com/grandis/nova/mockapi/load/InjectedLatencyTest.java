package com.grandis.nova.mockapi.load;

import static org.assertj.core.api.Assertions.assertThat;

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
}
