package com.grandis.nova.mockapi.load;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 발사 지연 기록. 목표 부하를 만들었는지 판정하는 근거라, 틀리면 무효인 실행이 유효로 찍힌다. */
class LoadRunnerTest {

    private final LoadRunner runner = new LoadRunner(LoadPlan.classify("http://localhost:8081", 10));

    @Test
    @DisplayName("예정보다 일찍 깨면 0 으로 본다")
    void earlyWakeIsZero() {
        runner.trackLag(-Duration.ofMillis(5).toNanos());
        assertThat(runner.maxLaunchLag()).isZero();
    }

    @Test
    @DisplayName("가장 크게 밀린 값을 남긴다 — 한 번이라도 크게 밀렸으면 그 실행은 무효다")
    void keepsMaximum() {
        runner.trackLag(Duration.ofMillis(3).toNanos());
        runner.trackLag(Duration.ofMillis(1200).toNanos());
        runner.trackLag(Duration.ofMillis(10).toNanos());
        assertThat(runner.maxLaunchLag()).isEqualTo(Duration.ofMillis(1200));
    }
}
