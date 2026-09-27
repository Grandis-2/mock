package com.grandis.nova.mockapi.load;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 부하 시험 결과를 모으고 보고서로 만든다.
 *
 * <p>보고서에 <b>실행 환경과 적용한 설정 버전을 함께 남긴다.</b> 요구사항 5.4 가 요구하는 것이고,
 * 없으면 나중에 그 숫자가 어떤 조건에서 나온 것인지 되짚을 수 없어 비교가 불가능해진다.
 *
 * <p>백분위는 <b>응답을 받은 요청만</b>으로 계산한다. 응답이 없는 요청에는 응답 지연이라는 값이
 * 없기 때문이다. 대신 결과 불명 건수를 따로 크게 적어, 지연이 좋아 보이는데 실은 느린 요청이
 * 타임아웃으로 빠진 상황을 숨기지 않는다.
 */
public final class LoadReport {

    /** 요청 하나의 결과. 응답을 못 받았으면 {@code status} 는 0 이다. */
    public record Attempt(Outcome outcome, int status, Duration latency) {
    }

    private final List<Attempt> attempts = new ArrayList<>();

    public synchronized void add(Attempt attempt) {
        attempts.add(attempt);
    }

    public int total() {
        return attempts.size();
    }

    public Map<Outcome, Integer> byOutcome() {
        Map<Outcome, Integer> counts = new EnumMap<>(Outcome.class);
        for (Outcome outcome : Outcome.values()) {
            counts.put(outcome, 0);
        }
        attempts.forEach(a -> counts.merge(a.outcome(), 1, Integer::sum));
        return counts;
    }

    /** 응답을 받은 것만 상태 코드별로. 거절의 내역을 나눠 보기 위한 것이다. */
    public Map<Integer, Integer> byStatus() {
        Map<Integer, Integer> counts = new TreeMap<>();
        attempts.stream()
                .filter(a -> a.status() > 0)
                .forEach(a -> counts.merge(a.status(), 1, Integer::sum));
        return counts;
    }

    /** 응답을 받은 요청 수. 0 이면 백분위는 값이 아니라 "측정 불가" 다. */
    public long responded() {
        return attempts.stream().filter(a -> a.status() > 0).count();
    }

    public Duration percentile(double p) {
        List<Duration> sorted = attempts.stream()
                .filter(a -> a.status() > 0)
                .map(Attempt::latency)
                .sorted(Comparator.naturalOrder())
                .toList();
        if (sorted.isEmpty()) {
            return Duration.ZERO;
        }
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.clamp(index, 0, sorted.size() - 1));
    }

    /**
     * 접수 성공을 제외한 비율. <b>분모는 보낸 요청 전체</b>다 — 미전송과 결과 불명을 빼지 않는다.
     */
    public double failureRate() {
        if (attempts.isEmpty()) {
            return 0.0;
        }
        int accepted = byOutcome().get(Outcome.ACCEPTED);
        return (double) (attempts.size() - accepted) / attempts.size();
    }

    /**
     * @param configVersion 이 실행에 적용된 Mock 설정 버전 (요구사항 5.4)
     * @param configBody    같은 목적. 지연·실패율을 그대로 남긴다
     * @param maxInFlight   동시에 떠 있던 요청의 최대치. 서버 쪽 커넥션 수의 대용값이다
     * @param elapsed       첫 발사부터 마지막 응답까지
     */
    public String render(LoadPlan plan, String configBody, int configVersion,
                         int maxInFlight, Duration elapsed) {
        Map<Outcome, Integer> counts = byOutcome();
        StringBuilder out = new StringBuilder();

        out.append("# 부하 시험 결과\n\n");
        out.append("> 이 실행의 조건은 아직 팀 합의를 거치지 않았다. 요구사항 8장에 따라\n")
                .append("> **성능 합격 판정의 근거로 쓸 수 없다.** 구조 확인용 실행이다.\n\n");

        out.append("## 실행 환경\n\n");
        out.append("| 항목 | 값 |\n| --- | --- |\n");
        out.append("| 일시 | ").append(java.time.ZonedDateTime.now()).append(" |\n");
        out.append("| OS | ").append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.version")).append(" |\n");
        out.append("| JVM | ").append(System.getProperty("java.version")).append(" |\n");
        out.append("| CPU 코어 | ").append(Runtime.getRuntime().availableProcessors()).append(" |\n");
        out.append("| 대상 | ").append(plan.baseUrl()).append(" |\n\n");

        out.append("## 적용한 Mock 설정 (요구사항 5.4)\n\n");
        out.append("| 항목 | 값 |\n| --- | --- |\n");
        out.append("| configVersion | ").append(configVersion).append(" |\n");
        out.append("| 설정 전문 | `").append(configBody).append("` |\n\n");

        out.append("## 조건\n\n");
        out.append("| 항목 | 값 | 합의 |\n| --- | --- | --- |\n");
        out.append("| 요청 규모 | ").append(plan.totalRequests()).append("건 | 미합의 |\n");
        out.append("| 발생 구간 | ").append(plan.rampUp()).append(" | 미합의 |\n");
        out.append("| 응답 타임아웃 | ").append(plan.responseTimeout()).append(" | 미합의 |\n");
        out.append("| 관찰 종료 조건 | 발사 후 ").append(plan.drainTimeout()).append(" | 미합의 |\n");
        out.append("| 허용 실패율 | ").append(plan.maxFailureRate()).append(" | 미합의 |\n");
        out.append("| p95 / p99 기준 | ").append(plan.p95Target()).append(" / ")
                .append(plan.p99Target()).append(" | 미합의 |\n\n");

        out.append("## 요청 분류 (요구사항 8장)\n\n");
        out.append("합계가 보낸 요청 수와 같아야 한다. 실패와 미전송을 결과에서 빼지 않는다.\n\n");
        out.append("8장의 네 분류에서 **명시적 거절을 둘로 나눴다.** 400 · 409 · 422 는 다시 보내도\n")
                .append("결과가 같은 확정 거절이고, 5xx 는 재시도 대상이라 본 서비스가 할 일이 정반대다.\n")
                .append("합쳐 두면 설정한 실패율이 실제로 몇 % 나왔는지가 거절 수에 묻힌다.\n\n");
        out.append("| 분류 | 건수 | 비율 | 8장 분류 |\n| --- | --- | --- | --- |\n");
        appendCount(out, "접수 성공", counts.get(Outcome.ACCEPTED), "접수 성공");
        appendCount(out, "명시적 거절 (400 · 409 · 422)", counts.get(Outcome.REJECTED), "명시적 거절");
        appendCount(out, "일시 실패 (5xx)", counts.get(Outcome.TRANSIENT_FAILURE), "명시적 거절");
        appendCount(out, "**결과 불명**", counts.get(Outcome.UNKNOWN), "결과 불명");
        appendCount(out, "미전송", counts.get(Outcome.NOT_SENT), "미전송");
        out.append("| 합계 | ").append(total()).append(" | 100.0% | |\n\n");

        out.append("## 거절 내역 (응답을 받은 것)\n\n");
        if (byStatus().isEmpty()) {
            out.append("응답을 받은 요청이 없다.\n\n");
        } else {
            out.append("| 상태 | 건수 |\n| --- | --- |\n");
            byStatus().forEach((status, count) ->
                    out.append("| ").append(status).append(" | ").append(count).append(" |\n"));
            out.append('\n');
        }

        out.append("## 지표\n\n");
        out.append("| 지표 | 값 | 기준 | 판정 |\n| --- | --- | --- | --- |\n");
        appendMetric(out, "p95", percentile(95), plan.p95Target(), responded());
        appendMetric(out, "p99", percentile(99), plan.p99Target(), responded());
        out.append("| 에러율 | ").append(percent(failureRate())).append(" | ")
                .append(percent(plan.maxFailureRate())).append(" | ")
                .append(failureRate() <= plan.maxFailureRate() ? "이내" : "초과").append(" |\n");
        out.append("| **미완료(결과 불명)** | ").append(counts.get(Outcome.UNKNOWN))
                .append("건 | — | — |\n");
        out.append("| 최대 동시 요청 | ").append(maxInFlight).append(" | — | — |\n");
        out.append("| 전체 소요 | ").append(elapsed.toMillis()).append("ms | — | — |\n\n");

        out.append("> 백분위는 응답을 받은 요청만으로 계산했다. 응답이 없는 요청에는 응답 지연이 없다.\n");
        out.append("> 서버 쪽 실제 커넥션 수는 클라이언트에서 볼 수 없다. 위 값은 동시에 떠 있던\n");
        out.append("> 요청의 최대치이며, 톰캣 커넥션은 서버에서 따로 관찰해야 한다.\n");
        return out.toString();
    }

    private void appendCount(StringBuilder out, String label, int count, String chapter8) {
        double ratio = total() == 0 ? 0 : (double) count / total();
        out.append("| ").append(label).append(" | ").append(count).append(" | ")
                .append(percent(ratio)).append(" | ").append(chapter8).append(" |\n");
    }

    /**
     * 응답이 하나도 없으면 값이 아니라 <b>측정 불가</b>로 적는다.
     *
     * <p>0ms 를 기준과 비교하면 "이내" 가 되어, 전부 타임아웃난 최악의 실행이 지연 기준을 통과한
     * 것처럼 보인다. 요구사항 8장이 실패를 결과에서 빼지 말라고 한 것과 정확히 반대되는 왜곡이다.
     */
    private static void appendMetric(StringBuilder out, String name, Duration actual,
                                     Duration target, long responded) {
        out.append("| ").append(name).append(" | ");
        if (responded == 0) {
            out.append("측정 불가 | ").append(target.toMillis()).append("ms | 응답 0건 |\n");
            return;
        }
        out.append(actual.toMillis()).append("ms | ").append(target.toMillis()).append("ms | ")
                .append(actual.compareTo(target) <= 0 ? "이내" : "초과").append(" |\n");
    }

    private static String percent(double ratio) {
        return String.format("%.1f%%", ratio * 100);
    }
}
