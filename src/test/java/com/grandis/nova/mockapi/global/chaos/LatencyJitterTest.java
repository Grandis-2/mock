package com.grandis.nova.mockapi.global.chaos;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 지연 지터. 요구사항이 정한 Mock 동작은 <b>"평균 500ms 지연"</b> 이라 상수가 아니라 분포다.
 *
 * <p>다른 단위 시험은 지터 0(`latency-jitter: 0.0`)으로 돌아야 "설정만큼 기다린다" 를 단정할 수
 * 있으므로, 지터를 켜는 이 시험만 컨텍스트를 따로 쓴다.
 *
 * <p><b>대기 시간을 재지 않고 뽑은 값 자체를 본다.</b> {@code Thread.sleep} 을 수만 번 재면
 * 스케줄러 오차가 분포보다 커져 무엇을 본 것인지 알 수 없고 시험이 몇 시간 걸린다.
 */
@SpringBootTest(properties = "mock.latency-jitter=0.4")
class LatencyJitterTest {

    private static final int MEAN_MS = 500;
    private static final double JITTER = 0.4;
    private static final int SAMPLES = 20_000;

    private static final long LOW = Math.round(MEAN_MS * (1 - JITTER));
    private static final long HIGH = Math.round(MEAN_MS * (1 + JITTER));

    @Autowired
    private DefaultFailureInjector injector;

    private List<Long> draw() {
        List<Long> waits = new ArrayList<>(SAMPLES);
        for (int i = 0; i < SAMPLES; i++) {
            waits.add(injector.jittered(MEAN_MS));
        }
        return waits;
    }

    /**
     * 평균이 설정값에서 벗어나면 "평균 500ms" 라고 말할 수 없다. 20,000 표본이면 이 균등분포의
     * 표준오차가 1ms 남짓이라 1% 여유는 넉넉하다.
     */
    @Test
    @DisplayName("설정값이 평균이 된다 — 20,000 표본에서 ±1% 안")
    void meanConvergesToConfigured() {
        double mean = draw().stream().mapToLong(Long::longValue).average().orElseThrow();

        assertThat(mean).isCloseTo(MEAN_MS, Offset.offset(MEAN_MS * 0.01));
    }

    /**
     * 범위를 벗어나면 부하 판정에서 주입한 몫을 빼낼 수 없다. 관측 p95 에서 뺄 값을
     * {@code 평균 × (1 + 지터 × 0.9)} 로 계산하는 전제가 이 경계다.
     */
    @Test
    @DisplayName("대기 시간이 평균 ± 지터 범위를 벗어나지 않는다")
    void staysWithinBounds() {
        assertThat(draw()).allSatisfy(wait -> assertThat(wait).isBetween(LOW, HIGH));
    }

    /** 상수였다면 지터를 넣은 의미가 없다. */
    @Test
    @DisplayName("값이 실제로 흔들린다 — 상수가 아니다")
    void actuallyVaries() {
        assertThat(draw().stream().distinct().count()).isGreaterThan(100);
    }

    /**
     * 지연 0 은 "지연 없음" 이지 "0 을 평균으로 흔든다" 가 아니다. 흔들면 음수 대기가 나온다.
     */
    @Test
    @DisplayName("지연 0 이면 흔들지 않는다")
    void zeroLatencyStaysZero() {
        assertThat(injector.jittered(0)).isZero();
    }
}
