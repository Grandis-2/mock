package com.grandis.nova.mockapi.load;

import com.grandis.nova.mockapi.global.error.ErrorCode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
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

    /**
     * 요청 하나의 결과. 응답을 못 받았으면 {@code status} 는 0 이다.
     *
     * @param externalKey    이 요청이 보낸 멱등 키. 끝나고 DB 를 <b>키로</b> 맞추기 위한 것이다
     * @param externalNumber 201 을 받았을 때 Mock 이 준 예약번호. 그 밖에는 null.
     *                       DB 에 적힌 번호와 같은지 봐야 "행은 있는데 다른 번호" 를 잡을 수 있다
     * @param injectedMs     이 요청에 Mock 이 실제로 넣은 지연({@code X-Mock-Injected-Latency-Ms}).
     *                       응답이 없거나 헤더가 없으면 null. 오버헤드는 요청마다 {@code latency − 이 값} 이다
     * @param detail         5xx 면 응답의 {@code errorMessage}, 응답을 못 받았으면 예외 종류. 그 밖에는 null.
     *                       같은 500 이라도 주입 실패와 Mock 의 진짜 오류(교착 등)를 가르는 데 쓴다
     */
    public record Attempt(String externalKey, String externalNumber,
                          Outcome outcome, int status, Duration latency, Long injectedMs, String detail) {

        /** 응답을 받지 못한 요청. 주입 지연을 알 수 없다. */
        public Attempt(String externalKey, String externalNumber,
                       Outcome outcome, int status, Duration latency) {
            this(externalKey, externalNumber, outcome, status, latency, null, null);
        }

        public Attempt(String externalKey, String externalNumber,
                       Outcome outcome, int status, Duration latency, Long injectedMs) {
            this(externalKey, externalNumber, outcome, status, latency, injectedMs, null);
        }
    }

    /**
     * 키로 모은다. 같은 요청의 결과가 두 번 들어오면 <b>먼저 것만 남는다.</b>
     *
     * <p>관찰 종료를 넘긴 요청은 바깥에서 결과 불명으로 적히고, 그때 걸린 인터럽트 때문에 보내던
     * 스레드도 한 번 더 적는다. 리스트에 쌓으면 한 요청이 두 번 세어져 <b>분류 합이 보낸 요청 수를
     * 넘는다.</b> 그 합이 합격 조건이므로 여기서 막는다.
     *
     * <p>먼저 것을 남기는 게 맞다. 실제 결과를 받은 쪽이 먼저 적고, 바깥은 아직 안 끝난 요청만
     * 적기 때문이다.
     */
    private final Map<String, Attempt> attempts = new LinkedHashMap<>();

    public synchronized void add(Attempt attempt) {
        attempts.putIfAbsent(attempt.externalKey(), attempt);
    }

    /** 결과가 기록된 요청 수. 보낸 요청 수와 같아야 한다. */
    public int total() {
        return attempts.size();
    }

    private Collection<Attempt> all() {
        return attempts.values();
    }

    public Map<Outcome, Integer> byOutcome() {
        Map<Outcome, Integer> counts = new EnumMap<>(Outcome.class);
        for (Outcome outcome : Outcome.values()) {
            counts.put(outcome, 0);
        }
        all().forEach(a -> counts.merge(a.outcome(), 1, Integer::sum));
        return counts;
    }

    /** 응답을 받은 것만 상태 코드별로. 거절의 내역을 나눠 보기 위한 것이다. */
    public Map<Integer, Integer> byStatus() {
        Map<Integer, Integer> counts = new TreeMap<>();
        all().stream()
                .filter(a -> a.status() > 0)
                .forEach(a -> counts.merge(a.status(), 1, Integer::sum));
        return counts;
    }

    /** 응답을 받은 요청 수. 0 이면 백분위는 값이 아니라 "측정 불가" 다. */
    public long responded() {
        return all().stream().filter(a -> a.status() > 0).count();
    }

    /** 관측 응답 지연의 백분위. 참고용이다 — 판정은 {@link #overheadPercentile} 로 한다. */
    public Duration percentile(double p) {
        List<Duration> sorted = all().stream()
                .filter(a -> a.status() > 0)
                .map(Attempt::latency)
                .sorted(Comparator.naturalOrder())
                .toList();
        return sorted.isEmpty() ? Duration.ZERO : nearestRank(sorted, p);
    }

    /**
     * <b>오버헤드의 백분위</b>. 요청마다 {@code 관측 − 그 요청에 실제로 뽑힌 지연} 을 구해 그 분포에서 뽑는다.
     *
     * <p>예전에는 {@code 관측 p95 − 주입 분포 p95} 로 셈했다. 지연을 흔들면 그건 오버헤드의 백분위가
     * 아니다 — 분위수는 더하거나 빼지지 않아서, p99 "오버헤드" 가 p95 보다 작게 나온 적도 있다.
     * 꼬리에 있는 요청이 오래 걸린 이유가 지연이 길게 뽑혀서인지 Mock 이 느려서인지 요청 단위로 갈라야
     * Mock 의 꼬리가 보인다.
     *
     * @return 헤더를 받은 응답이 하나도 없으면 null (측정 불가)
     */
    public Long overheadPercentile(double p) {
        List<Long> sorted = all().stream()
                .filter(a -> a.status() > 0 && a.injectedMs() != null)
                .map(a -> a.latency().toMillis() - a.injectedMs())
                .sorted()
                .toList();
        return sorted.isEmpty() ? null : nearestRank(sorted, p);
    }

    /** 응답은 받았는데 주입 지연 헤더가 없는 수. 0 이어야 한다 — 있으면 그만큼 오버헤드를 모른다. */
    public long respondedWithoutInjected() {
        return all().stream().filter(a -> a.status() > 0 && a.injectedMs() == null).count();
    }

    /** 최근접 순위 백분위. 보간하지 않는다 — 실제로 관측된 값 중 하나를 돌려준다. */
    private static <T> T nearestRank(List<T> sorted, double p) {
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
     * 클라이언트가 본 결과와 DB 가 어긋난 한 건.
     *
     * @param reason 무엇이 어긋났는지. 보고서에 그대로 찍는다
     */
    public record Mismatch(String externalKey, Outcome outcome, int status, String reason) {
    }

    /**
     * <b>합격의 본체.</b> 클라이언트가 본 결과를 DB 와 <b>키로</b> 맞춘다.
     *
     * <p>건수만 맞춰 보면 201 키 하나가 빠지고 5xx 키 하나가 들어가도 개수가 같아 최악의 버그를
     * 놓친다. 요구사항 8장이 "건수 합계만 비교하지 않음" 으로 정한 이유다.
     *
     * <p>규칙은 모드와 무관하다. {@code failureMode} 가 HTTP_5XX 든 TIMEOUT 든, 주입한 실패는
     * 전부 커밋 전이므로 5xx 키에는 행이 없어야 한다.
     *
     * <ul>
     *   <li>201 — 행이 있고 <b>번호까지 같아야</b> 한다
     *   <li>400 · 5xx · 미전송 — 행이 없어야 한다
     *   <li>결과 불명 — 있든 없든 맞다. 커밋 뒤 응답만 유실됐을 수도, 커밋 전에 끊겼을 수도 있다
     *   <li>409 · 422 — <b>나오면 그 자체가 결함이다.</b> 요청마다 새 키를 쓰므로 취소 표식도
     *       기존 등록도 있을 수 없다
     * </ul>
     *
     * <p>{@code 접수 성공 ≤ DB 행 ≤ 접수 성공 + 결과 불명} 은 이 규칙에서 저절로 따라온다.
     * 그 부등식을 따로 걸지 않는 이유는, 등호가 성립하는 조건이 모드와 부하에 따라 달라져
     * 계약을 지켰는데도 깨질 수 있기 때문이다.
     */
    public List<Mismatch> verifyAgainst(RegistrationSnapshot db) {
        List<Mismatch> mismatches = new ArrayList<>();
        for (Attempt a : all()) {
            switch (a.outcome()) {
                case ACCEPTED -> checkAccepted(a, db, mismatches);
                case REJECTED -> checkRejected(a, db, mismatches);
                case TRANSIENT_FAILURE, NOT_SENT -> checkAbsent(a, db, mismatches);
                case UNKNOWN -> { /* 있든 없든 맞다 */ }
            }
        }
        return mismatches;
    }

    private static void checkAccepted(Attempt a, RegistrationSnapshot db, List<Mismatch> out) {
        if (!db.has(a.externalKey())) {
            out.add(new Mismatch(a.externalKey(), a.outcome(), a.status(),
                    "201 을 받았는데 행이 없다 — 커밋되지 않은 성공"));
            return;
        }
        String stored = db.numberOf(a.externalKey());
        if (a.externalNumber() == null) {
            out.add(new Mismatch(a.externalKey(), a.outcome(), a.status(),
                    "201 응답에 예약번호가 없다 (DB: " + stored + ")"));
        } else if (!a.externalNumber().equals(stored)) {
            out.add(new Mismatch(a.externalKey(), a.outcome(), a.status(),
                    "번호가 다르다 — 응답 " + a.externalNumber() + " · DB " + stored));
        }
    }

    /** 새 키에 409·422 가 나올 수 없다. 나왔다면 키가 겹쳤거나 Mock 이 남의 행을 보고 있다. */
    private static void checkRejected(Attempt a, RegistrationSnapshot db, List<Mismatch> out) {
        if (a.status() == 409 || a.status() == 422) {
            out.add(new Mismatch(a.externalKey(), a.outcome(), a.status(),
                    "새 키에 " + a.status() + " 가 나왔다 — 취소 표식도 기존 등록도 있을 수 없다"));
            return;
        }
        checkAbsent(a, db, out);
    }

    private static void checkAbsent(Attempt a, RegistrationSnapshot db, List<Mismatch> out) {
        if (db.has(a.externalKey())) {
            out.add(new Mismatch(a.externalKey(), a.outcome(), a.status(),
                    "행이 없어야 하는데 있다 (번호 " + db.numberOf(a.externalKey()) + ")"));
        }
    }

    /**
     * 결과 불명 중 실제로는 DB 에 남은 것. <b>이 프로젝트의 핵심 장면이다.</b>
     *
     * <p>클라이언트에게는 둘 다 "응답 없음" 으로 똑같은데, 남은 쪽은 재시도하면 중복 등록이 되고
     * 안 남은 쪽은 재시도해야 한다. 그래서 워커가 {@code by-key} 로 먼저 확인해야 한다.
     */
    public long unknownWithRow(RegistrationSnapshot db) {
        return all().stream()
                .filter(a -> a.outcome() == Outcome.UNKNOWN)
                .filter(a -> db.has(a.externalKey()))
                .count();
    }

    /**
     * 우리가 보낸 키가 아닌 행. 판정 전에 초기화했으면 0 이어야 한다. <b>이번 실행의</b> 확인용 등록은 우리가 넣은
     * 것이라 세지 않는다. 접두사만 보고 빼면 앞 실행이 남긴 확인용 행(초기화를 빠뜨린 흔적)이 숨는다.
     */
    public int foreignRows(RegistrationSnapshot db, Canary canary) {
        return (int) db.numbersByKey().keySet().stream()
                .filter(key -> !attempts.containsKey(key))
                .filter(key -> canary == null || !key.equals(canary.key()))
                .count();
    }

    // ---------------------------------------------------------------- 판정

    /**
     * 주입 실패의 {@code errorMessage}(api.md 500 원인표). 같은 500 이라도 이 문구가 아니면 Mock 의 진짜 오류다 —
     * 교착 · 재시도 상한 · 유지 시간 뒤의 빈 500. 예전에는 모두 "5%" 에 섞여 들어갔다(리뷰 H3 ④).
     */
    static final String INJECTED_FAILURE_MESSAGE = ErrorCode.UPSTREAM_UNAVAILABLE.defaultMessage();

    /** 원장이 이 Mock 의 것인지 확인하려고 부하 직전에 넣는 등록의 키 접두사. 원장에서 눈으로 가려내는 표식이다. */
    public static final String CANARY_PREFIX = "canary-";

    /** 99.9% 양측 구간의 z. 실패율이 우연으로 범위를 벗어날 확률을 0.1% 로 둔다. */
    private static final double Z_999 = 3.29;

    /** 부하 직전에 넣은 확인용 등록. 끝나고 원장에 이 키 · 번호가 있어야 그 원장이 이 Mock 의 것이다. */
    public record Canary(String key, String number) {
    }

    /**
     * 판정에 필요한 실행 조건.
     *
     * @param db      원장. 조회에 실패했으면 null 이고 이유는 {@code dbError} 에 있다
     * @param canary  확인용 등록. 넣지 못했으면 null
     */
    public record Facts(String failureMode, double failureRate, InjectedLatency injected,
                        Duration launchLag, RegistrationSnapshot db, String dbError, Canary canary) {
    }

    /** 주입 실패가 아닌 5xx. 0 이어야 한다. */
    public long nonInjectedServerErrors() {
        return all().stream()
                .filter(a -> a.outcome() == Outcome.TRANSIENT_FAILURE)
                .filter(a -> !INJECTED_FAILURE_MESSAGE.equals(a.detail()))
                .count();
    }

    /** 접수되지 않은 요청을 상세(5xx 문구 · 예외 종류)별로. 원인을 Mock 과 PC 중 어디서 찾을지 정한다. */
    public Map<String, Integer> notAcceptedByDetail() {
        Map<String, Integer> counts = new TreeMap<>();
        all().stream()
                .filter(a -> a.outcome() != Outcome.ACCEPTED)
                .forEach(a -> counts.merge(a.outcome() + " · " + (a.detail() == null ? "—" : a.detail()),
                        1, Integer::sum));
        return counts;
    }

    /**
     * 규칙마다 통과 · 실패를 정한다. 문서에만 있던 판정 규칙을 코드로 옮긴 것이다(리뷰 H3 ③).
     *
     * <p>시나리오마다 규칙이 다르다. {@code HTTP_5XX} 는 결과 불명이 0 이어야 하고 5xx 가 실패율만큼 나와야
     * 하며, {@code TIMEOUT} 은 결과 불명이 전부여야 하고 그 키들이 원장에 하나도 없어야 한다(커밋 전에 끊는다).
     * 예전에는 결과 불명이 대조에서 빠져, {@code timeout} 에서 끊기 전에 커밋하는 회귀가 생겨도 통과했다(⑤).
     */
    public Verdict judge(LoadPlan plan, Facts f) {
        Map<Outcome, Integer> counts = byOutcome();
        int sent = plan.totalRequests();
        int unknown = counts.get(Outcome.UNKNOWN);
        int notSent = counts.get(Outcome.NOT_SENT);
        boolean http5xx = "HTTP_5XX".equals(f.failureMode());
        RegistrationSnapshot db = f.db();
        List<Verdict.Check> checks = new ArrayList<>();

        // 이 실행이 판정할 자격이 있는가
        checks.add(validity("목표 부하 — 발사 지연 ≤ " + plan.maxLaunchLag().toMillis() + "ms",
                plan.targetLoadAchieved(f.launchLag()), f.launchLag().toMillis() + "ms"));
        checks.add(validity("미전송 0건 — 부하가 서버에 다 닿았다", notSent == 0, notSent + "건"));
        if (http5xx) {
            checks.add(validity("시나리오가 응답 타임아웃 안에 든다", plan.leavesRoomFor(f.injected()),
                    "주입 최대 " + f.injected().percentileMs(100) + "ms + 허용 "
                            + plan.maxP99Overhead().toMillis() + "ms · 타임아웃 "
                            + plan.responseTimeout().toMillis() + "ms"));
        }
        checks.add(validity("원장 조회", db != null, db != null ? db.rowCount() + "행" : f.dbError()));
        boolean canaryFound = db != null && f.canary() != null && db.has(f.canary().key())
                && f.canary().number().equals(db.numberOf(f.canary().key()));
        checks.add(validity("원장이 이 Mock 의 것이다 — 확인용 등록이 같은 번호로 있다", canaryFound,
                f.canary() == null ? "확인용 등록을 넣지 못했다" : "`" + f.canary().key() + "`"));
        if (db != null) {
            checks.add(validity("집계 전 원장이 잦아들었다", db.quiesced(), db.waited().toSeconds() + "초 대기"));
        }

        // Mock 이 계약대로 답했는가
        checks.add(contract("분류 합 = 보낸 요청 수", total() == sent, total() + " / " + sent));
        if (db != null) {
            int mismatches = verifyAgainst(db).size();
            checks.add(contract("키 대조 위반 0건", mismatches == 0, mismatches + "건"));
            int foreign = foreignRows(db, f.canary());
            checks.add(contract("우리 키가 아닌 행 0건", foreign == 0, foreign + "건"));
        }
        long withoutHeader = respondedWithoutInjected();
        checks.add(contract("주입 지연 헤더 없는 응답 0건", withoutHeader == 0, withoutHeader + "건"));
        long nonInjected = nonInjectedServerErrors();
        checks.add(contract("주입이 아닌 5xx 0건 — 처리 못 한 오류 · 재시도 상한 · 빈 500",
                nonInjected == 0, nonInjected + "건"));
        if (http5xx) {
            checks.add(contract("결과 불명 ≤ " + percent(plan.maxUnknownRate()),
                    unknown <= Math.floor(sent * plan.maxUnknownRate()), unknown + "건"));
            int injectedFailures = counts.get(Outcome.TRANSIENT_FAILURE) - (int) nonInjected;
            checks.add(failureRateCheck(injectedFailures, (int) responded(), f.failureRate()));
        } else {
            checks.add(f.failureRate() >= 1.0
                    ? contract("결과 불명 100%", unknown == sent, unknown + " / " + sent)
                    : rangeCheck("결과 불명이 실패율 " + f.failureRate() + " 의 99.9% 범위", unknown, sent,
                    f.failureRate()));
            if (db != null) {
                long kept = unknownWithRow(db);
                checks.add(contract("결과 불명 키의 행 0건 — 커밋 전에 끊는다", kept == 0, kept + "건"));
            }
        }

        // Mock 이 충분히 빠른가 — 지연 판정 패스만 본다. 분류 판정 패스는 타임아웃이 워커 값이라 꼬리가 잘린다
        if (plan.pass() == LoadPlan.Pass.LATENCY) {
            checks.add(overheadCheck("p95", overheadPercentile(95), plan.maxP95Overhead()));
            checks.add(overheadCheck("p99", overheadPercentile(99), plan.maxP99Overhead()));
        }
        return Verdict.of(checks);
    }

    /**
     * 실패율 판정. "정확히 5%" 를 요구하지 않는다(요구사항 5.4). 5,000건 · 5% 면 우연만으로도 199 ~ 301건
     * 사이에서 흔들리므로, 이항분포의 99.9% 범위 안이면 통과로 본다. 그 밖이면 주사위가 설정과 다르게 굴렀다.
     */
    static Verdict.Check failureRateCheck(int injectedFailures, int n, double rate) {
        if (rate <= 0) {
            return contract("실패율 0 → 5xx 0건", injectedFailures == 0, injectedFailures + "건");
        }
        if (rate >= 1) {
            return contract("실패율 100% → 전부 5xx", injectedFailures == n, injectedFailures + " / " + n);
        }
        return rangeCheck("5xx 가 실패율 " + rate + " 의 99.9% 범위", injectedFailures, n, rate);
    }

    private static Verdict.Check rangeCheck(String name, int observed, int n, double rate) {
        double mean = n * rate;
        double sd = Math.sqrt(n * rate * (1 - rate));
        long low = (long) Math.floor(mean - Z_999 * sd);
        long high = (long) Math.ceil(mean + Z_999 * sd);
        return contract(name, observed >= low && observed <= high,
                observed + "건 (" + percent((double) observed / Math.max(n, 1)) + ") · 범위 " + low + " ~ " + high);
    }

    private static Verdict.Check overheadCheck(String name, Long overheadMs, Duration target) {
        if (overheadMs == null) {
            return new Verdict.Check(Verdict.Kind.METRIC, name + " 오버헤드 ≤ " + target.toMillis() + "ms",
                    false, "측정 불가 — 헤더를 받은 응답 0건");
        }
        return new Verdict.Check(Verdict.Kind.METRIC, name + " 오버헤드 ≤ " + target.toMillis() + "ms",
                overheadMs <= target.toMillis(), overheadMs + "ms");
    }

    private static Verdict.Check validity(String name, boolean ok, String detail) {
        return new Verdict.Check(Verdict.Kind.VALIDITY, name, ok, detail);
    }

    private static Verdict.Check contract(String name, boolean ok, String detail) {
        return new Verdict.Check(Verdict.Kind.CONTRACT, name, ok, detail);
    }

    /**
     * @param configVersion 이 실행에 적용된 Mock 설정 버전 (요구사항 5.4)
     * @param configBody    같은 목적. 지연·실패율을 그대로 남긴다
     * @param facts         실행 중에 모은 사실 — 주입 지연의 분포(시나리오가 타임아웃에 걸치는지 · 동시 요청 상한),
     *                      원장, 확인용 등록. 오버헤드는 분포가 아니라 요청마다 받은 헤더로 계산한다
     * @param heldFraction  Mock 이 응답 없이 붙잡는 요청의 비율. 동시 요청 상한을 계산하는 데 쓴다
     * @param maxInFlight   동시에 떠 있던 요청의 최대치. 서버 쪽 커넥션 수의 대용값이다
     * @param elapsed       첫 발사부터 마지막 응답까지
     * @param verdict       {@link #judge} 의 결과. 보고서 맨 위에 찍는다
     * @param runLabel      이 실행이 판정인지 예열인지. 예열은 결과를 버리므로 제목에 밝힌다 — 보고서만 보고 판정
     *                      15회를 골라낼 수 있어야 한다
     */
    public String render(LoadPlan plan, String configBody, int configVersion, Facts facts,
                         double heldFraction, int maxInFlight, Duration elapsed, Verdict verdict, String runLabel) {
        InjectedLatency injected = facts.injected();
        Duration launchLag = facts.launchLag();
        RegistrationSnapshot db = facts.db();
        Map<Outcome, Integer> counts = byOutcome();
        StringBuilder out = new StringBuilder();

        out.append("# 부하 시험 결과 — ").append(runLabel).append(" · ").append(plan.pass()).append("\n\n");
        appendVerdict(out, verdict);
        boolean targetLoad = plan.targetLoadAchieved(launchLag);
        if (!targetLoad) {
            out.append("> ## ⚠ 목표 부하를 만들지 못했다 — 성능 판정에서 뺀다\n>\n");
            out.append("> 계획한 발사 시각보다 최대 **").append(launchLag.toMillis())
                    .append("ms** 늦게 쐈다(한계 ").append(plan.maxLaunchLag().toMillis())
                    .append("ms). 5,000건을 10초에 고르게 쏘는 것이 전제인데 그만큼 밀렸다는 건\n")
                    .append("> 클라이언트나 PC 가 멈칫했다는 뜻이다. 요구사항 8장이 *\"목표 부하를 만들지 못한\n")
                    .append("> 시험은 성능 합격으로 판정하지 않는다\"* 고 정했다.\n>\n");
            out.append("> **아래 계약 판정(키 대조 · 분류 합)은 그대로 본다.** 부하가 덜 걸렸다고 계약이\n")
                    .append("> 깨져도 되는 것은 아니다.\n\n");
        }
        appendNotSentWarning(out, counts.get(Outcome.NOT_SENT));
        if (!plan.leavesRoomFor(injected)) {
            out.append("> ## ⚠ 시나리오가 응답 타임아웃에 걸쳐 있다 — 결과 불명을 Mock 탓으로 읽지 않는다\n>\n");
            out.append("> 주입한 지연이 최대 **").append(injected.percentileMs(100))
                    .append("ms** 까지 뽑히고 허용 오버헤드(p99 ").append(plan.maxP99Overhead().toMillis())
                    .append("ms)를 더하면 응답 타임아웃 ").append(plan.responseTimeout().toMillis())
                    .append("ms 를 넘는다.\n> Mock 이 기준대로 빨라도 꼬리의 요청은 결과 불명이 된다. ")
                    .append("시나리오의 지연을 낮추거나 타임아웃을 조정해야 판정에 쓸 수 있다.\n\n");
        }
        out.append("> 조건은 2026-09-28 합의됐다. **이 실행이 판정하는 것은 ")
                .append(plan.pass() == LoadPlan.Pass.CLASSIFY ? "분류" : "응답 지연")
                .append("이다.**\n");
        out.append("> 응답 타임아웃 ").append(plan.responseTimeout().toSeconds())
                .append("초는 ")
                .append(plan.pass() == LoadPlan.Pass.CLASSIFY
                        ? "워커 읽기 타임아웃이다(be worker read-timeout 5s · api.md 계약). 워커가 겪을 결과를 센다."
                        : "백분위가 잘리지 않게 넉넉히 둔 값이다. 분류는 참고로만 본다.")
                .append("\n\n");

        out.append("## 실행 환경\n\n");
        out.append("| 항목 | 값 |\n| --- | --- |\n");
        out.append("| 일시 | ").append(java.time.ZonedDateTime.now()).append(" |\n");
        out.append("| OS | ").append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.version")).append(" |\n");
        out.append("| JVM | ").append(System.getProperty("java.version")).append(" |\n");
        out.append("| CPU 코어 | ").append(Runtime.getRuntime().availableProcessors()).append(" |\n");
        out.append("| 대상 | ").append(plan.baseUrl()).append(" |\n");
        // 판정 숫자를 가장 크게 흔든 조건이다 — 커밋마다 디스크 동기화(1 · 1)면 Docker Desktop 에서 COMMIT 이 p99 200ms 까지
        // 걸려 풀이 막혔다. 다른 설정으로 돌린 숫자를 같은 조건으로 읽지 않게 적는다(docs/load-test.md).
        out.append("| MySQL 커밋 동기화 | ")
                .append(db == null ? "원장을 읽지 못해 모름" : db.commitDurability())
                .append(" |\n\n");

        out.append("## 적용한 Mock 설정 (요구사항 5.4)\n\n");
        out.append("| 항목 | 값 |\n| --- | --- |\n");
        out.append("| configVersion | ").append(configVersion).append(" |\n");
        out.append("| 지연 평균 · 지터 | ").append(injected.describe()).append(" |\n");
        out.append("| 유지 시간 · 워커 타임아웃 | ").append(field(configBody, "timeoutHoldMs")).append("ms · ")
                .append(field(configBody, "workerReadTimeoutMs")).append("ms |\n");
        out.append("| 커넥션 풀 | 클라이언트에서 볼 수 없다 — Mock 의 `maximum-pool-size` 를 함께 적는다(합의값 30) |\n");
        out.append("| 설정 전문 | `").append(configBody).append("` |\n\n");

        out.append("## 조건 (2026-09-28 합의)\n\n");
        out.append("| 항목 | 값 | 비고 |\n| --- | --- | --- |\n");
        out.append("| 판정 대상 | ").append(plan.pass()).append(" | 두 패스로 나눠 돌린다 |\n");
        out.append("| 요청 규모 | ").append(plan.totalRequests()).append("건 | 과제 예시 |\n");
        out.append("| 발생 구간 | ").append(plan.rampUp()).append(" | 균등 발사 |\n");
        out.append("| 응답 타임아웃 | ").append(plan.responseTimeout()).append(" | ")
                .append(plan.pass() == LoadPlan.Pass.CLASSIFY ? "워커 읽기 타임아웃" : "잘린 분포 방지")
                .append(" |\n");
        out.append("| 관찰 종료 조건 | 발사 후 ").append(plan.drainTimeout())
                .append(" | 응답 타임아웃보다 길어야 한다 (코드가 강제) |\n");
        out.append("| 결과 불명 허용 | ").append(percent(plan.maxUnknownRate()))
                .append(" | HTTP_5XX 모드. 응답이 없다는 것은 제시간에 못 답했다는 뜻이다 |\n");
        out.append("| 오버헤드 기준 | p95 ").append(plan.maxP95Overhead().toMillis())
                .append("ms · p99 ").append(plan.maxP99Overhead().toMillis())
                .append("ms | 요청마다 관측 − 그 요청에 뽑힌 지연, 그 분포의 백분위. 2026-09-28 실측으로 확정 |\n");
        out.append("| 발사 지연 한계 | ").append(plan.maxLaunchLag().toMillis())
                .append("ms | 넘으면 목표 부하를 만들지 못한 실행이다 |\n\n");

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

        appendVerification(out, plan, counts, db, facts.canary());

        out.append("## 거절 내역 (응답을 받은 것)\n\n");
        if (byStatus().isEmpty()) {
            out.append("응답을 받은 요청이 없다.\n\n");
        } else {
            out.append("| 상태 | 건수 |\n| --- | --- |\n");
            byStatus().forEach((status, count) ->
                    out.append("| ").append(status).append(" | ").append(count).append(" |\n"));
            out.append('\n');
        }

        Map<String, Integer> details = notAcceptedByDetail();
        if (!details.isEmpty()) {
            out.append("## 접수되지 않은 요청의 상세\n\n");
            out.append("5xx 는 응답의 `errorMessage`, 응답을 못 받은 것은 예외 종류다. 주입 실패(`")
                    .append(INJECTED_FAILURE_MESSAGE).append("`)가 아닌 5xx 는 Mock 의 진짜 오류이고, ")
                    .append("예외 종류는 원인을 Mock 과 PC 중 어디서 찾을지 가른다.\n\n");
            out.append("| 분류 · 상세 | 건수 |\n| --- | --- |\n");
            details.forEach((detail, count) ->
                    out.append("| ").append(detail).append(" | ").append(count).append(" |\n"));
            out.append('\n');
        }

        out.append("## 지표\n\n");
        out.append("주입한 지연은 **").append(injected.describe()).append("** 이다. **요청마다** 관측 응답 지연에서 ")
                .append("그 요청에 실제로 뽑힌 지연(`X-Mock-Injected-Latency-Ms`)을 빼고, 그 분포의 백분위를 ")
                .append("**오버헤드**로 본다 — Mock 이 실제로 쓴 시간의 꼬리다. 관측 백분위는 참고로만 적는다.\n\n");
        out.append("| 지표 | 관측 (참고) | 오버헤드 | 기준 | 판정 |\n")
                .append("| --- | --- | --- | --- | --- |\n");
        appendOverhead(out, "p95", percentile(95), overheadPercentile(95), plan.maxP95Overhead());
        appendOverhead(out, "p99", percentile(99), overheadPercentile(99), plan.maxP99Overhead());
        long withoutHeader = respondedWithoutInjected();
        if (withoutHeader > 0) {
            out.append("| ⚠ 주입 지연 헤더 없는 응답 | ").append(withoutHeader)
                    .append("건 | — | 0건 | 그만큼 오버헤드를 모른다 |\n");
        }
        out.append("| 에러율(접수 성공 제외) | ").append(percent(failureRate()))
                .append(" | — | — | — | 참고 |\n");
        out.append("| **미완료(결과 불명)** | ").append(counts.get(Outcome.UNKNOWN))
                .append("건 | — | — | — | 위 대조 참고 |\n");
        out.append("| 최대 동시 요청 | ").append(maxInFlight).append(" | — | — | ")
                .append(expectedInFlight(plan, injected.meanMs(), heldFraction))
                .append(" | 넘으면 어딘가에서 대기가 쌓였다 |\n");
        out.append("| 전체 소요 | ").append(elapsed.toMillis()).append("ms | — | — | — | — |\n\n");

        out.append("> 백분위는 응답을 받은 요청만으로 계산했다. 응답이 없는 요청에는 응답 지연이 없다.\n");
        out.append("> 서버 쪽 실제 커넥션 수는 클라이언트에서 볼 수 없다. 위 값은 동시에 떠 있던\n");
        out.append("> 요청의 최대치이며, 톰캣 커넥션은 서버에서 따로 관찰해야 한다.\n");
        return out.toString();
    }

    /** 어긋난 키를 몇 건까지 예시로 찍을지. 건수만 적으면 어느 키인지 찾을 수 없다. */
    private static final int MISMATCH_SAMPLES = 10;

    /**
     * 미전송이 있으면 <b>부하가 서버에 다 도달하지 못한 것</b>이라 경고한다. 판정에서 빼지는 않는다.
     *
     * <p>발사 지연만으로는 이 경우를 못 잡는다. 가상 스레드가 제시간에 깨어나 요청을 내보내는 것은
     * 싸서, 메모리가 모자라도 발사는 늦지 않는다. 막히는 곳은 그 다음 — 연결을 맺는 단계다.
     * 2026-09-28 RAM 5.85GB PC 에서 발사 지연은 한계 이내인데 미전송이 36.7% 나온 적이 있다.
     *
     * <p>자동으로 빼지 않는 이유 — 원인이 <b>Mock 이 연결을 못 받은 것</b>이면 그건 Mock 의 실패인데,
     * 빼면 숨기게 된다. 클라이언트 쪽 원인인지 Mock 쪽 원인인지를 적은 뒤에야 판정에 쓸 수 있다.
     */
    private void appendNotSentWarning(StringBuilder out, int notSent) {
        if (notSent == 0) {
            return;
        }
        out.append("> ## ⚠ 미전송 ").append(notSent).append("건 — 부하가 서버에 다 도달하지 못했다\n>\n");
        out.append("> 연결조차 맺지 못한 요청이다. 목표 부하가 서버에 온전히 걸리지 않았으므로 **원인을\n")
                .append("> 가리기 전에는 성능 판정에 쓰지 않는다.** 클라이언트 쪽(메모리 · 소켓)이면 측정 환경\n")
                .append("> 문제이고, Mock 이 연결을 못 받은 것이면 Mock 의 실패다. 발사 지연은 이 경우를 잡지\n")
                .append("> 못한다 — 발사는 제시간에 되고 연결 단계에서 막히기 때문이다.\n\n");
    }

    /**
     * 동시에 떠 있을 요청 수의 상한. {@code RPS × 한 건이 떠 있는 시간} 이다.
     *
     * <p>한 건이 떠 있는 시간은 두 경우로 갈린다.
     * <ul>
     *   <li>Mock 이 답하는 요청 — {@code 설정 지연 + 허용 오버헤드}
     *   <li>Mock 이 <b>일부러 붙잡는</b> 요청({@code TIMEOUT} 모드에서 주사위에 걸린 것) — 클라이언트가
     *       포기할 때까지 떠 있으므로 {@code 응답 타임아웃 + 허용 오버헤드}
     * </ul>
     * 둘을 붙잡는 비율로 섞는다. {@code timeout} 시나리오(100% 붙잡음)는 응답 타임아웃 × 500 RPS 가 정상인데
     * (워커 5초면 ≈ 2,500, 3초를 가정하던 때는 ≈ 1,500), 붙잡는 요청을 빼고 계산하면 상한이 125 로 나와
     * "대기가 쌓였다" 로 잘못 읽혔다.
     *
     * <p>관측치가 이것을 넘으면 요청이 어딘가에서 기다리며 쌓였다는 뜻이다. 2026-09-28 판정에서
     * 커넥션 풀 20 일 때 baseline 관측 480(상한 ~375), latency 1,342(상한 ~1,125) 였고, 풀을 30 으로
     * 올리자 303 · 1,057 로 상한 안에 들어왔다.
     *
     * <p>다만 넘었다고 원인이 Mock 이라는 뜻은 아니다. PC 가 멈칫해도 넘는다. 그 구분은 발사 지연이 한다.
     *
     * @param heldFraction Mock 이 응답 없이 붙잡는 요청의 비율. {@code TIMEOUT} 모드면 실패율, 아니면 0
     */
    private static String expectedInFlight(LoadPlan plan, int registerLatencyMs, double heldFraction) {
        if (plan.rampUp().isZero()) {
            return "—";
        }
        double rps = (double) plan.totalRequests() / plan.rampUp().toSeconds();
        long overheadMs = plan.maxP95Overhead().toMillis();
        double answeredSec = (registerLatencyMs + overheadMs) / 1000.0;
        double heldSec = (plan.responseTimeout().toMillis() + overheadMs) / 1000.0;
        double perRequestSec = (1 - heldFraction) * answeredSec + heldFraction * heldSec;
        return "상한 ~" + Math.round(rps * perRequestSec);
    }

    /** 맨 위에 판정 한 줄과 규칙별 결과. 숫자를 읽고 사람이 판정하던 것을 코드가 대신한다. */
    private static void appendVerdict(StringBuilder out, Verdict verdict) {
        out.append("## 판정: **").append(verdict.result().label()).append("**\n\n");
        out.append("| 종류 | 규칙 | 값 | 결과 |\n| --- | --- | --- | --- |\n");
        for (Verdict.Check check : verdict.checks()) {
            String kind = switch (check.kind()) {
                case VALIDITY -> "유효성";
                case CONTRACT -> "계약";
                case METRIC -> "지표";
            };
            out.append("| ").append(kind).append(" | ").append(check.name()).append(" | ")
                    .append(check.detail()).append(" | ").append(check.ok() ? "통과" : "**실패**").append(" |\n");
        }
        out.append("\n> 유효성이 깨지면 **판정 불가**(이 실행으로는 판정할 수 없다), 나머지가 깨지면 **FAIL** 이다.\n")
                .append("> 종료 코드 PASS 0 · FAIL 1 · 판정 불가 2.\n\n");
    }

    /**
     * 합격의 본체를 적는다. 지표(p95 · 에러율)보다 이쪽이 먼저다.
     *
     * <p>Mock 이 증명할 것은 빠르다는 게 아니라 <b>부하 중에도 "실패는 커밋 전" 이라는 약속을
     * 지켰다는 것</b>이다.
     */
    private void appendVerification(StringBuilder out, LoadPlan plan,
                                    Map<Outcome, Integer> counts, RegistrationSnapshot db, Canary canary) {
        if (db == null) {
            // 조회에 실패해도 보고서는 남긴다. 실패한 실행을 기록에서 빼면 "조회 실패" 가 "불일치 0건" 처럼 보인다.
            out.append("## DB 대조 (합격의 본체)\n\n**원장을 읽지 못했다 — 대조하지 않았다.** ")
                    .append("이유는 위 판정 표의 \"원장 조회\" 행에 있다. 이 실행은 판정 불가다.\n\n");
            return;
        }
        List<Mismatch> mismatches = verifyAgainst(db);
        boolean sumMatches = total() == plan.totalRequests();
        int foreign = foreignRows(db, canary);
        long unknownKept = unknownWithRow(db);

        out.append("## DB 대조 (합격의 본체)\n\n");
        out.append("클라이언트가 본 결과를 등록 원장과 **키로** 맞춘다. 건수만 비교하면 201 키 하나가\n")
                .append("빠지고 5xx 키 하나가 들어가도 개수가 같다 (요구사항 8장 \"건수 합계만 비교하지 않음\").\n\n");

        out.append("| 조건 | 값 | 판정 |\n| --- | --- | --- |\n");
        out.append("| 분류 합 = 보낸 요청 수 | ").append(total()).append(" / ")
                .append(plan.totalRequests()).append(" | ").append(verdict(sumMatches)).append(" |\n");
        out.append("| 키 대조 위반 | ").append(mismatches.size()).append("건 | ")
                .append(verdict(mismatches.isEmpty())).append(" |\n");
        out.append("| 우리 키가 아닌 행 | ").append(foreign).append("건 | ")
                .append(verdict(foreign == 0)).append(" |\n");
        out.append("| 원장 총 행 수 | ").append(db.rowCount()).append("건 | — |\n");
        out.append("| 집계 전 잦아듦 | ").append(db.quiesced() ? "확인" : "**멎지 않았다**")
                .append(" (").append(db.waited().toSeconds()).append("초 대기) | ")
                .append(verdict(db.quiesced())).append(" |\n\n");

        out.append("### 결과 불명의 두 종류\n\n");
        out.append("클라이언트에게는 둘 다 \"응답 없음\" 으로 똑같다. 그런데 한쪽은 등록됐고 한쪽은 안 됐다.\n")
                .append("결과 불명일 때 워커가 할 수 있는 판단은 \"다시 보낸다\" 뿐이라, 남은 쪽이었다면 그\n")
                .append("재시도가 **중복 등록**이 된다. `by-key` 조회가 왜 필수인지의 실측 근거다.\n\n");
        int unknown = counts.get(Outcome.UNKNOWN);
        out.append("| | 건수 |\n| --- | --- |\n");
        out.append("| 결과 불명 · **DB 에 남음** (커밋 뒤 응답만 유실) | ").append(unknownKept).append(" |\n");
        out.append("| 결과 불명 · DB 에 없음 (커밋 전에 끊김) | ").append(unknown - unknownKept).append(" |\n\n");

        if (!mismatches.isEmpty()) {
            out.append("### 위반 내역\n\n");
            out.append("| 키 | 분류 | 상태 | 무엇이 어긋났나 |\n| --- | --- | --- | --- |\n");
            mismatches.stream().limit(MISMATCH_SAMPLES).forEach(m ->
                    out.append("| `").append(m.externalKey()).append("` | ").append(m.outcome())
                            .append(" | ").append(m.status() == 0 ? "—" : m.status())
                            .append(" | ").append(m.reason()).append(" |\n"));
            if (mismatches.size() > MISMATCH_SAMPLES) {
                out.append("\n위 ").append(MISMATCH_SAMPLES).append("건은 예시다. 전체 ")
                        .append(mismatches.size()).append("건.\n");
            }
            out.append('\n');
        }
    }

    private static String verdict(boolean ok) {
        return ok ? "통과" : "**실패**";
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
    private static void appendOverhead(StringBuilder out, String name, Duration observed,
                                       Long overheadMs, Duration target) {
        out.append("| ").append(name).append(" | ");
        if (overheadMs == null) {
            out.append("측정 불가 | — | ").append(target.toMillis()).append("ms | 헤더를 받은 응답 0건 |\n");
            return;
        }
        out.append(observed.toMillis()).append("ms | ").append(overheadMs).append("ms | ")
                .append(target.toMillis()).append("ms | ")
                .append(overheadMs <= target.toMillis() ? "이내" : "초과").append(" |\n");
    }

    /** 설정 응답에서 숫자 필드 하나. 예전 Mock 처럼 필드가 없으면 "?" — 어떤 조건이었는지 모른다는 표시다. */
    private static String field(String configBody, String name) {
        var matcher = java.util.regex.Pattern.compile("\"" + name + "\"\\s*:\\s*([0-9.]+)").matcher(configBody);
        return matcher.find() ? matcher.group(1) : "?";
    }

    private static String percent(double ratio) {
        return String.format("%.1f%%", ratio * 100);
    }
}
