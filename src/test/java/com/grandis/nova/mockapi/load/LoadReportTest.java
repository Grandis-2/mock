package com.grandis.nova.mockapi.load;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 부하 판정의 계산. 하네스는 빌드에서 돌지 않으므로, 판정이 틀려도 여기서 잡지 않으면 아무도 모른다.
 */
class LoadReportTest {

    /**
     * 리뷰 H3 ① — 관측 백분위에서 주입 백분위를 빼면 꼬리를 가린다.
     *
     * <p>100건 중 98건은 Mock 이 30ms 를 쓰고, <b>2건은 400ms</b> 를 쓴다. 그런데 그 2건은 지연이
     * 짧게(300ms) 뽑혀 관측 700ms 로 정상 요청들 사이에 섞인다. 관측 분포의 꼬리는 지연이 길게 뽑힌
     * 정상 요청들이 차지하므로 "관측 p99 − 주입 p99" 는 30ms 근처로 나온다.
     */
    @Test
    @DisplayName("오버헤드 백분위는 요청마다 뺀 값의 분포에서 뽑는다 — 주입이 짧게 뽑힌 느린 요청을 가리지 않는다")
    void perRequestOverheadShowsHiddenTail() {
        var report = new LoadReport();
        for (int i = 0; i < 98; i++) {
            long injected = 300 + i * 4;                       // 300 ~ 688ms
            add(report, "k" + i, injected, injected + 30);
        }
        add(report, "slow-1", 300, 700);
        add(report, "slow-2", 300, 700);

        long oldWay = report.percentile(99).toMillis() - 696;  // 관측 p99 − 주입 분포 p99(300~700 균등)
        assertThat(oldWay).as("예전 계산은 느린 요청을 못 본다").isLessThan(100);

        assertThat(report.overheadPercentile(95)).isEqualTo(30L);
        assertThat(report.overheadPercentile(99)).as("요청마다 빼면 꼬리가 보인다").isEqualTo(400L);
    }

    @Test
    @DisplayName("헤더를 받은 응답이 없으면 오버헤드는 측정 불가(null) 다 — 0 으로 통과시키지 않는다")
    void noInjectedHeaderMeansUnmeasured() {
        var report = new LoadReport();
        report.add(new LoadReport.Attempt("k", "R-1", Outcome.ACCEPTED, 201, Duration.ofMillis(530)));

        assertThat(report.overheadPercentile(95)).isNull();
        assertThat(report.respondedWithoutInjected()).isEqualTo(1);
    }

    @Test
    @DisplayName("응답이 없는 요청은 오버헤드 분포에 들어가지 않는다")
    void unansweredExcluded() {
        var report = new LoadReport();
        add(report, "ok", 500, 540);
        report.add(new LoadReport.Attempt("lost", null, Outcome.UNKNOWN, 0, Duration.ofSeconds(5)));

        assertThat(report.overheadPercentile(99)).isEqualTo(40L);
        assertThat(report.respondedWithoutInjected()).isZero();
    }

    private static void add(LoadReport report, String key, long injectedMs, long observedMs) {
        report.add(new LoadReport.Attempt(key, "R-" + key, Outcome.ACCEPTED, 201,
                Duration.ofMillis(observedMs), injectedMs));
    }
}
