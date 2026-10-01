package com.grandis.nova.mockapi.load;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    // ---------------------------------------------------------------- 판정 (리뷰 H3 ③ ④ ⑤)

    private static final String URL = "http://localhost:8081";
    private static final String INJECTED = LoadReport.INJECTED_FAILURE_MESSAGE;
    private static final LoadReport.Canary CANARY = new LoadReport.Canary("canary-1", "R-canary");

    /** 100건 · 실패율 5% 의 정상 실행 — 95건 접수(행 있음), 주입 5xx 5건(행 없음), 확인용 등록 1행. */
    @Test
    @DisplayName("정상 실행은 PASS 다")
    void healthyRunPasses() {
        var report = new LoadReport();
        var rows = new HashMap<String, String>(Map.of(CANARY.key(), CANARY.number()));
        fillHttp5xx(report, rows, 95, 5);

        Verdict verdict = report.judge(LoadPlan.classify(URL, 100), http5xx(0.05, rows));

        assertThat(verdict.result()).as(failures(verdict)).isEqualTo(Verdict.Result.PASS);
    }

    @Test
    @DisplayName("주입이 아닌 500(처리 못 한 오류)이 하나라도 있으면 FAIL — 5% 에 섞이지 않는다")
    void unhandledServerErrorFails() {
        var report = new LoadReport();
        var rows = new HashMap<String, String>(Map.of(CANARY.key(), CANARY.number()));
        fillHttp5xx(report, rows, 95, 4);
        report.add(new LoadReport.Attempt("deadlock", null, Outcome.TRANSIENT_FAILURE, 500,
                Duration.ofMillis(510), 500L, "Mock 이 처리하지 못한 오류입니다."));

        Verdict verdict = report.judge(LoadPlan.classify(URL, 100), http5xx(0.05, rows));

        assertThat(verdict.result()).isEqualTo(Verdict.Result.FAIL);
        assertThat(failed(verdict)).anyMatch(name -> name.startsWith("주입이 아닌 5xx"));
    }

    /** 1,000건 · 5% 면 99.9% 범위는 27 ~ 73건이다. 100건은 주사위가 설정과 다르게 구른 것이다. */
    @Test
    @DisplayName("5xx 가 실패율의 99.9% 범위를 벗어나면 FAIL")
    void failureRateOutOfRangeFails() {
        assertThat(LoadReport.failureRateCheck(50, 1000, 0.05).ok()).isTrue();
        assertThat(LoadReport.failureRateCheck(100, 1000, 0.05).ok()).isFalse();
        assertThat(LoadReport.failureRateCheck(1, 1000, 0.0).ok()).as("실패율 0 이면 1건도 안 된다").isFalse();
    }

    /** 리뷰 H3 ⑤ — "끊기 전에 커밋" 회귀. 예전에는 결과 불명이 대조에서 빠져 통과했다. */
    @Test
    @DisplayName("timeout — 결과 불명 키가 하나라도 원장에 있으면 FAIL")
    void timeoutUnknownWithRowFails() {
        var report = new LoadReport();
        var rows = new HashMap<String, String>(Map.of(CANARY.key(), CANARY.number()));
        for (int i = 0; i < 10; i++) {
            report.add(new LoadReport.Attempt("t" + i, null, Outcome.UNKNOWN, 0, Duration.ofSeconds(5)));
        }
        rows.put("t3", "R-leaked");

        Verdict verdict = report.judge(LoadPlan.classify(URL, 10), timeout(rows));

        assertThat(verdict.result()).isEqualTo(Verdict.Result.FAIL);
        assertThat(failed(verdict)).anyMatch(name -> name.startsWith("결과 불명 키의 행 0건"));
    }

    /** 리뷰 H3 ⑤ — JDBC 인자를 빠뜨려 빈 원장을 읽으면 "결과 불명 키의 행 0" 이 저절로 통과한다. */
    @Test
    @DisplayName("timeout — 확인용 등록이 원장에 없으면 엉뚱한 DB 다. 판정 불가")
    void wrongLedgerIsInvalid() {
        var report = new LoadReport();
        for (int i = 0; i < 10; i++) {
            report.add(new LoadReport.Attempt("t" + i, null, Outcome.UNKNOWN, 0, Duration.ofSeconds(5)));
        }

        Verdict verdict = report.judge(LoadPlan.classify(URL, 10), timeout(new HashMap<>()));

        assertThat(verdict.result()).isEqualTo(Verdict.Result.INVALID);
        assertThat(failed(verdict)).anyMatch(name -> name.startsWith("원장이 이 Mock 의 것이다"));
    }

    @Test
    @DisplayName("원장 조회가 실패하면 판정 불가이고, 보고서는 그래도 끝까지 나온다")
    void dbFailureIsInvalidButReported() {
        var report = new LoadReport();
        fillHttp5xx(report, new HashMap<>(), 95, 5);
        var facts = new LoadReport.Facts("HTTP_5XX", 0.05, new InjectedLatency(500, 0.4),
                Duration.ZERO, null, "SQLException: Communications link failure", CANARY);
        LoadPlan plan = LoadPlan.classify(URL, 100);

        Verdict verdict = report.judge(plan, facts);
        String rendered = report.render(plan, "{}", 1, facts, 0.0, 300, Duration.ofSeconds(11), verdict, "판정");

        assertThat(verdict.result()).isEqualTo(Verdict.Result.INVALID);
        assertThat(rendered).contains("판정 불가").contains("원장을 읽지 못했다").contains("Communications link failure");
    }

    @Test
    @DisplayName("발사가 1초 넘게 밀렸으면 목표 부하를 못 만든 실행이다. 판정 불가")
    void launchLagIsInvalid() {
        var report = new LoadReport();
        var rows = new HashMap<String, String>(Map.of(CANARY.key(), CANARY.number()));
        fillHttp5xx(report, rows, 95, 5);
        var facts = new LoadReport.Facts("HTTP_5XX", 0.05, new InjectedLatency(500, 0.4),
                Duration.ofMillis(1500), snapshot(rows), null, CANARY);

        assertThat(report.judge(LoadPlan.classify(URL, 100), facts).result()).isEqualTo(Verdict.Result.INVALID);
    }

    /** 지연 판정 패스만 오버헤드를 판정한다. 분류 판정 패스는 타임아웃이 워커 값이라 꼬리가 잘린다. */
    @Test
    @DisplayName("지연 판정 패스 — 요청별 p99 오버헤드가 400ms 를 넘으면 FAIL")
    void latencyPassJudgesOverhead() {
        var report = new LoadReport();
        var rows = new HashMap<String, String>(Map.of(CANARY.key(), CANARY.number()));
        for (int i = 0; i < 100; i++) {
            long overhead = i < 98 ? 30 : 900;
            report.add(new LoadReport.Attempt("k" + i, "R-k" + i, Outcome.ACCEPTED, 201,
                    Duration.ofMillis(500 + overhead), 500L));
            rows.put("k" + i, "R-k" + i);
        }

        Verdict classify = report.judge(LoadPlan.classify(URL, 100), http5xx(0.0, rows));
        Verdict latency = report.judge(LoadPlan.latency(URL, 100), http5xx(0.0, rows));

        assertThat(classify.result()).as("분류 패스는 오버헤드로 실패시키지 않는다").isEqualTo(Verdict.Result.PASS);
        assertThat(latency.result()).isEqualTo(Verdict.Result.FAIL);
        assertThat(failed(latency)).containsExactly("p99 오버헤드 ≤ 400ms");
    }

    private static void fillHttp5xx(LoadReport report, Map<String, String> rows, int accepted, int injectedFailures) {
        for (int i = 0; i < accepted; i++) {
            add(report, "a" + i, 500, 540);
            rows.put("a" + i, "R-a" + i);
        }
        for (int i = 0; i < injectedFailures; i++) {
            report.add(new LoadReport.Attempt("f" + i, null, Outcome.TRANSIENT_FAILURE, 500,
                    Duration.ofMillis(530), 500L, INJECTED));
        }
    }

    private static LoadReport.Facts http5xx(double failureRate, Map<String, String> rows) {
        return new LoadReport.Facts("HTTP_5XX", failureRate, new InjectedLatency(500, 0.4),
                Duration.ofMillis(20), snapshot(rows), null, CANARY);
    }

    private static LoadReport.Facts timeout(Map<String, String> rows) {
        return new LoadReport.Facts("TIMEOUT", 1.0, new InjectedLatency(0, 0.4),
                Duration.ofMillis(20), snapshot(rows), null, CANARY);
    }

    private static RegistrationSnapshot snapshot(Map<String, String> rows) {
        return new RegistrationSnapshot(Map.copyOf(rows), true, Duration.ofSeconds(8));
    }

    private static List<String> failed(Verdict verdict) {
        return verdict.checks().stream().filter(c -> !c.ok()).map(Verdict.Check::name).toList();
    }

    private static String failures(Verdict verdict) {
        return "실패한 규칙: " + failed(verdict);
    }

    private static void add(LoadReport report, String key, long injectedMs, long observedMs) {
        report.add(new LoadReport.Attempt(key, "R-" + key, Outcome.ACCEPTED, 201,
                Duration.ofMillis(observedMs), injectedMs));
    }
}
