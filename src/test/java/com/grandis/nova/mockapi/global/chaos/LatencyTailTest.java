package com.grandis.nova.mockapi.global.chaos;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 지연 꼬리. 꼬리를 걸어도 <b>전체 평균은 설정값 그대로</b>여야 한다(요구사항 "평균 500ms").
 *
 * <p>{@link LatencyJitterTest} 와 같은 이유로 대기 시간을 재지 않고 뽑은 값 자체를 본다. 지터 0.4 로 같은 컨텍스트를
 * 쓴다 — 몸통의 지터와 꼬리가 섞인 실제 모양을 본다.
 */
@SpringBootTest(properties = "mock.latency-jitter=0.4")
class LatencyTailTest {

    private static final int MEAN_MS = 500;
    private static final double JITTER = 0.4;

    /**
     * 꼬리가 섞이면 표준편차가 커진다(2% · 2~4초면 약 380ms). 200,000 표본이면 평균의 표준오차가 1ms 남짓이라
     * 1% 여유는 넉넉하다. 뽑기만 하므로 금방 끝난다.
     */
    private static final int SAMPLES = 200_000;

    /** 합의한 두 시나리오 — 느린 꼬리(2% · 2~4초)와 워커 타임아웃을 넘는 꼬리(0.5% · 5.5~6.5초). */
    private static final LatencyTail TAIL = new LatencyTail(0.02, 2000, 4000);
    private static final LatencyTail OVER_TIMEOUT = new LatencyTail(0.005, 5500, 6500);

    @Autowired
    private DefaultFailureInjector injector;

    private long[] draw(LatencyTail tail) {
        long[] waits = new long[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            waits[i] = injector.drawn(MEAN_MS, tail);
        }
        return waits;
    }

    @Test
    @DisplayName("몸통 평균은 꼬리만큼 낮춘 값이다 — 2% · 2~4초면 약 449ms, 꼬리가 없으면 평균 그대로")
    void bodyMeanCompensatesForTail() {
        assertThat(TAIL.bodyMeanMs(MEAN_MS)).isCloseTo((500 - 0.02 * 3000) / 0.98, Offset.offset(1e-9));
        assertThat(TAIL.bodyMeanMs(MEAN_MS)).isCloseTo(449.0, Offset.offset(0.1));
        assertThat(LatencyTail.NONE.bodyMeanMs(MEAN_MS)).isEqualTo(MEAN_MS);
    }

    @Test
    @DisplayName("꼬리를 걸어도 전체 평균은 설정값이다 — 200,000 표본에서 ±1% 안")
    void overallMeanStaysConfigured() {
        for (LatencyTail tail : new LatencyTail[]{TAIL, OVER_TIMEOUT}) {
            double mean = Arrays.stream(draw(tail)).average().orElseThrow();

            assertThat(mean).as(tail.toString()).isCloseTo(MEAN_MS, Offset.offset(MEAN_MS * 0.01));
        }
    }

    /** 몸통 최대(629)와 꼬리 최소(2000) 사이가 비어 있어 꼬리에 걸린 수를 셀 수 있다. */
    @Test
    @DisplayName("꼬리에 걸리는 비율이 설정값이다 — 2% ± 0.2%p")
    void tailRateMatches() {
        long tailed = Arrays.stream(draw(TAIL)).filter(wait -> wait >= TAIL.minMs()).count();

        assertThat((double) tailed / SAMPLES).isCloseTo(TAIL.rate(), Offset.offset(0.002));
    }

    /**
     * 몸통도 꼬리도 아닌 값이 나오면 부하 판정의 사전 검사("몸통 최대 + 허용 오버헤드 < 타임아웃" ·
     * "꼬리 최소 > 타임아웃")가 전제를 잃는다. 몸통은 보정된 평균 × (1 ∓ 지터), 꼬리는 구간 그대로다.
     */
    @Test
    @DisplayName("값은 몸통 구간이나 꼬리 구간 안에만 나온다")
    void staysWithinBodyOrTail() {
        double body = TAIL.bodyMeanMs(MEAN_MS);
        long bodyLow = Math.round(body * (1 - JITTER));
        long bodyHigh = Math.round(body * (1 + JITTER));

        assertThat(Arrays.stream(draw(TAIL)).boxed().toList()).allSatisfy(wait -> assertThat(
                (wait >= bodyLow && wait <= bodyHigh) || (wait >= TAIL.minMs() && wait <= TAIL.maxMs()))
                .as("%dms 는 몸통 %d~%d · 꼬리 %d~%d 밖이다", wait, bodyLow, bodyHigh, TAIL.minMs(), TAIL.maxMs())
                .isTrue());
    }

    /** 꼬리 없음은 지금 동작 그대로여야 한다. 기본값이라 15회 판정의 조건이 여기 걸려 있다. */
    @Test
    @DisplayName("꼬리 없음이면 지금과 같다 — 평균 ± 지터 안")
    void noTailIsUnchanged() {
        long low = Math.round(MEAN_MS * (1 - JITTER));
        long high = Math.round(MEAN_MS * (1 + JITTER));

        assertThat(Arrays.stream(draw(LatencyTail.NONE)).boxed().toList()).allSatisfy(wait -> assertThat(wait).isBetween(low, high));
        assertThat(injector.drawn(0, LatencyTail.NONE)).isZero();
    }
}
