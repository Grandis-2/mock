package com.grandis.nova.mockapi.load;

import com.grandis.nova.mockapi.global.chaos.LatencyTail;
import com.grandis.nova.mockapi.registration.RegisterBodies;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 부하 시험 실행기. {@code ./gradlew loadTest} 로 돌린다.
 *
 * <p>JUnit 시험이 아니다. 일반 빌드에서 같이 돌면 안 되기 때문이다 — 수천 건을 보내는 데 수십 초가
 * 걸리고, 결과가 환경에 따라 달라 빌드 성패의 기준이 될 수 없다.
 *
 * <p><b>Mock 을 미리 띄워 두어야 한다.</b> 이 실행기는 서버를 기동하지 않는다. 부하를 거는 쪽과 받는
 * 쪽이 같은 JVM 에 있으면 서로 자원을 뺏어 무엇을 측정한 것인지 알 수 없다.
 *
 * <p>기본 시나리오다(꼬리 시나리오는 아래 {@link #scenarioConfig}).
 * <ul>
 *   <li>{@code baseline} — 기본 설정 그대로. 평소 부하
 *   <li>{@code latency} — 지연 평균 1500ms · 실패율 0. <b>관측 지연이 설정값과 비슷한지</b> 본다.
 *       크게 벗어나면 Mock 이 병목이라는 뜻이고, 그러면 본 서비스 측정도 믿을 수 없다.
 *       <br>2000ms 였는데 지연이 평균으로 바뀌며(NV-121) 1500 으로 내렸다. 워커 타임아웃을 3초로
 *       가정하던 때라 2000 이면 주입 최대 2800ms 에 오버헤드가 붙어 타임아웃을 넘었다. 워커가 5초로
 *       정해진 지금은 2000 도 들어가지만, 앞 판정과 견줄 수 있게 1500 을 둔다
 *   <li>{@code timeout} — 실패율 1.0 · TIMEOUT. 응답 없는 연결이 쌓이는지 본다
 *   <li>{@code mixed} — baseline 에서 모드만 MIXED(NV-312). 5% 가 500 · 본문 없는 500 · HTML 502~504 로 섞여도
 *       판정이 baseline 과 같은지 본다 — 주입한 실패를 응답 문구가 아니라 표식 헤더로 가르는지가 핵심이다
 * </ul>
 */
public final class LoadTestMain {

    private static final String DEFAULT_BASE_URL = "http://localhost:8081";
    private static final String CONFIG = "/external/config";

    /** 합의된 요청 규모. 과제 예시 그대로다. 적은 수로 배선만 확인할 때는 네 번째 인자로 넘긴다. */
    private static final int AGREED_REQUESTS = 5_000;

    /**
     * 등록 원장을 직접 읽는 곳의 기본값. {@code application.yml.example} 과 같다.
     *
     * <p>목록 API 가 있어도 시험 대상(Mock)의 응답에 기대지 않고 원장 자체를 보려고 스키마를 읽기 전용으로
     * 조회한다. 이유는 {@link RegistrationSnapshot} 에 적었다.
     *
     * <p><b>부하를 쏘는 쪽과 Mock 이 다른 장비면 반드시 인자로 넘겨야 한다.</b> 합의 조건 1번이
     * 장비 분리인데, 기본값의 {@code localhost} 는 부하를 쏘는 장비를 가리켜 원장이 비어 보이고
     * 키 대조가 전부 실패로 찍힌다. {@code compose.yaml} 이 포트를 모든 인터페이스에 열어두므로
     * Mock 장비의 주소만 넣으면 된다.
     */
    private static final String DEFAULT_JDBC_URL =
            "jdbc:mysql://localhost:3307/external_mock?serverTimezone=UTC";
    private static final String DEFAULT_DB_USER = "nova";
    private static final String DEFAULT_DB_PASSWORD = "nova";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private LoadTestMain() {
    }

    public static void main(String[] rawArgs) throws Exception {
        // --warmup 은 어디에 붙여도 된다. 예열 실행은 결과를 버리므로 보고서에 그렇게 적고, 판정과 상관없이 0 으로 끝낸다
        // (식은 JVM 이라 FAIL 이 나는 게 정상이다 — 스크립트가 거기서 멈추면 안 된다).
        boolean warmup = java.util.Arrays.asList(rawArgs).contains("--warmup");
        String[] args = java.util.Arrays.stream(rawArgs).filter(a -> !"--warmup".equals(a)).toArray(String[]::new);
        String baseUrl = args.length > 0 ? args[0] : DEFAULT_BASE_URL;
        String scenario = args.length > 1 ? args[1] : "baseline";
        String pass = args.length > 2 ? args[2] : "classify";
        int requests = args.length > 3 ? Integer.parseInt(args[3]) : AGREED_REQUESTS;
        String jdbcUrl = args.length > 4 ? args[4] : DEFAULT_JDBC_URL;
        String dbUser = args.length > 5 ? args[5] : DEFAULT_DB_USER;
        String dbPassword = args.length > 6 ? args[6] : DEFAULT_DB_PASSWORD;

        // 인자부터 검사한다. 잘못된 시나리오 · 패스로 Mock 설정만 바꿔 놓고 끝나지 않게 Mock 을 건드리기 전에 거른다.
        String scenarioConfig = scenarioConfig(scenario);
        LoadPlan plan = switch (pass) {
            case "classify" -> LoadPlan.classify(baseUrl, requests);
            case "latency" -> LoadPlan.latency(baseUrl, requests);
            default -> throw new IllegalArgumentException(
                    "패스는 classify 또는 latency 여야 합니다. 받은 값: " + pass);
        };

        // 시나리오를 걸기 전에 확인용 등록을 하나 넣는다. 끝나고 원장에서 이 키를 찾지 못하면 엉뚱한 DB 를
        // 읽은 것이다 — JDBC 인자를 빠뜨려 빈 원장을 읽어도 timeout 시나리오의 "결과 불명 키의 행 0" 은
        // 통과해 버린다(리뷰 H3 ⑤).
        LoadReport.Canary canary = registerCanary(baseUrl);
        put(baseUrl + CONFIG, scenarioConfig);
        String configBody = get(baseUrl + CONFIG);
        String failureMode = readFailureMode(configBody);
        double failureRate = readDecimal(configBody, "failureRate", 0.0);
        int configVersion = readConfigVersion(configBody);
        // 지연은 평균값이라 요청마다 흔들린다. 흔드는 폭을 알아야 관측값에서 주입한 몫을 뺄 수 있다.
        // 평균화 이전 Mock 은 이 필드가 없으므로 0(고정)으로 본다. 꼬리를 모르는 Mock 이면 꼬리 없음이다.
        InjectedLatency injected = new InjectedLatency(
                readNumber(configBody, "registerLatencyMs"),
                readDecimal(configBody, "latencyJitter", 0.0),
                readTail(configBody));
        // TIMEOUT 모드에서 주사위에 걸린 요청은 Mock 이 응답 없이 붙잡는다. 그만큼은 클라이언트가
        // 포기할 때까지 떠 있으므로 동시 요청 상한을 계산할 때 따로 센다.
        double heldFraction = "TIMEOUT".equals(failureMode) ? failureRate : 0.0;

        System.out.printf("시나리오 %s · 패스 %s · %d건 / %s · 타임아웃 %s · configVersion %d%n",
                scenario, plan.pass(), plan.totalRequests(), plan.rampUp(),
                plan.responseTimeout(), configVersion);
        System.out.println("주입한 지연: " + injected.describe());
        if (!plan.leavesRoomFor(injected)) {
            // 쏘기 전에 알린다. 5,000건을 다 쏘고 나서야 알면 실행 하나를 버린다.
            System.out.println("⚠ " + plan.roomDetail(injected) + " — 응답 타임아웃을 넘는다. "
                    + "꼬리의 요청은 Mock 이 빨라도 결과 불명이 된다.");
        }
        System.out.println("설정: " + configBody);

        LoadRunner runner = new LoadRunner(plan);
        Duration elapsed = runner.run();

        // 클라이언트가 멈춰도 서버는 계속 처리한다. 잦아들기를 기다린 뒤 원장을 떠 온다.
        // 조회에 실패해도 멈추지 않는다 — 보고서를 남겨야 "조회 실패" 가 "불일치 0건" 처럼 사라지지 않는다.
        System.out.println("등록 원장이 잦아들기를 기다린다... (" + jdbcUrl + ")");
        RegistrationSnapshot db = null;
        String dbError = null;
        try {
            db = RegistrationSnapshot.take(jdbcUrl, dbUser, dbPassword);
        } catch (RuntimeException e) {
            // 드라이버 메시지는 여러 줄이라 그대로 두면 보고서의 판정 표가 깨진다. 한 줄로 편다.
            dbError = (e.getClass().getSimpleName() + ": " + e.getMessage()).replaceAll("\\s+", " ").trim();
            System.out.println("⚠ 원장을 읽지 못했다 — " + dbError);
        }

        LoadReport.Facts facts = new LoadReport.Facts(failureMode, failureRate, injected,
                runner.maxLaunchLag(), db, dbError, canary);
        Verdict verdict = runner.report().judge(plan, facts);
        String report = runner.report().render(plan, configBody, configVersion, facts, heldFraction,
                runner.maxInFlight(), elapsed, verdict, warmup ? "예열 (결과를 버린다)" : "판정");
        System.out.println();
        System.out.println(report);

        // 파일 이름에 패스와 예열 여부를 넣는다. 이름만으로 어느 실행인지 갈라야 판정 15회를 골라낼 수 있다.
        Path out = Path.of("build", "load",
                (warmup ? "warmup-" : "report-") + scenario + "-" + pass + "-"
                        + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                        + ".md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report);
        System.out.println("보고서: " + out.toAbsolutePath());
        System.out.println((warmup ? "예열 (참고) " : "") + "판정: " + verdict.result().label());
        // 스크립트로 여러 번 돌릴 때 보고서를 열지 않고 거른다. PASS 0 · FAIL 1 · 판정 불가 2. 예열은 늘 0.
        // ./gradlew loadTest 를 거치면 Gradle 이 0 이 아닌 값을 모두 1 로 끝내므로, 그때는 위의 "판정: …" 줄로 가른다.
        System.exit(warmup ? 0 : verdict.result().exitCode());
    }

    /**
     * 확인용 등록. 지연 · 실패를 끈 채 하나 넣고 받은 번호를 돌려준다. 실패하면 null 이고, 판정은 "판정 불가" 가 된다.
     *
     * <p>설정을 바꾸므로 반드시 시나리오를 걸기 전에 부른다. 이 키 하나만 대조에서 빠진다({@link LoadReport#foreignRows}).
     */
    private static LoadReport.Canary registerCanary(String baseUrl) {
        String key = LoadReport.CANARY_PREFIX + java.util.UUID.randomUUID();
        try {
            put(baseUrl + CONFIG, """
                    {"registerLatencyMs":0,"failureRate":0.0,"failureMode":"HTTP_5XX"}""");
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/external/reservations"))
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", key)
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(RegisterBodies.of(key)))
                    .build();
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            var number = java.util.regex.Pattern.compile("\"externalNumber\"\\s*:\\s*\"([^\"]+)\"")
                    .matcher(response.body());
            if (response.statusCode() != 201 || !number.find()) {
                System.out.println("⚠ 확인용 등록 실패 — " + response.statusCode() + " " + response.body());
                return null;
            }
            return new LoadReport.Canary(key, number.group(1));
        } catch (Exception e) {
            System.out.println("⚠ 확인용 등록 실패 — " + e);
            return null;
        }
    }

    /**
     * 시나리오에 맞는 Mock 설정 본문. 없는 시나리오면 예외 — Mock 에 아무것도 보내기 전에 부른다.
     *
     * <p>설정 경로에는 지연·실패를 주입하지 않으므로 실패율 1.0 상태에서도 되돌릴 수 있다.
     *
     * <p>설정 PUT 자체가 실패했을 때 원래 값으로 되돌리지는 않는다. 정상 실행도 끝난 뒤 시나리오 설정을 그대로
     * 두고, 남는 것은 확인용 등록의 지연 0 · 실패 0 이라 다음 실행을 흔들지 않는다. 같은 PUT 이 실패한 상황이면
     * 되돌리는 PUT 도 실패하기 쉽다.
     */
    private static String scenarioConfig(String scenario) {
        return switch (scenario) {
            case "baseline" -> """
                    {"registerLatencyMs":500,"failureRate":0.05,"failureMode":"HTTP_5XX"}""";
            case "latency" -> """
                    {"registerLatencyMs":1500,"failureRate":0.0,"failureMode":"HTTP_5XX"}""";
            case "timeout" -> """
                    {"registerLatencyMs":0,"failureRate":1.0,"failureMode":"TIMEOUT"}""";
            // baseline 에서 모드만 MIXED(NV-312). 판정 규칙은 HTTP_5XX 와 같다(LoadReport.judge)
            case "mixed" -> """
                    {"registerLatencyMs":500,"failureRate":0.05,"failureMode":"MIXED"}""";
            // 느린 꼬리(NV-260). baseline 에 2% · 2~4초를 얹는다. 4000 + 허용 400 < 5000 이라 결과 불명은 0 이어야 한다
            case "tail" -> """
                    {"registerLatencyMs":500,"failureRate":0.05,"failureMode":"HTTP_5XX",
                     "latencyTailRate":0.02,"latencyTailMinMs":2000,"latencyTailMaxMs":4000}""";
            // 워커 타임아웃 뒤 늦은 커밋(NV-260). 꼬리 하한 5500 > 5000 이라 꼬리 요청은 전부 결과 불명이고 원장에는
            // 남아야 한다. 실패율 0 — 꼬리 요청이 주사위에 걸리면 "결과 불명 키는 전부 ACTIVE" 를 단정할 수 없다.
            // classify 패스만 판정에 쓴다(latency 패스 10초면 꼬리도 제시간이다)
            case "tail-over-timeout" -> """
                    {"registerLatencyMs":500,"failureRate":0.0,"failureMode":"HTTP_5XX",
                     "latencyTailRate":0.005,"latencyTailMinMs":5500,"latencyTailMaxMs":6500}""";
            default -> throw new IllegalArgumentException(
                    "시나리오는 baseline · latency · timeout · mixed · tail · tail-over-timeout 중 하나여야 합니다. 받은 값: "
                            + scenario);
        };
    }

    /** 설정 응답의 꼬리 세 칸. 꼬리를 모르는 Mock 이거나 비율이 0 이면 꼬리 없음이다. */
    private static LatencyTail readTail(String configBody) {
        double rate = readDecimal(configBody, "latencyTailRate", 0.0);
        if (rate <= 0) {
            return LatencyTail.NONE;
        }
        return new LatencyTail(rate, readNumber(configBody, "latencyTailMinMs"),
                readNumber(configBody, "latencyTailMaxMs"));
    }

    private static void put(String url, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(5))
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "설정 적용 실패 " + response.statusCode() + " — " + response.body());
        }
    }

    private static String get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    private static int readConfigVersion(String configBody) {
        return readNumber(configBody, "configVersion");
    }

    /**
     * 적용된 실패 모드 이름 그대로. 판정 규칙이 모드마다 다르다(LoadReport.judge).
     *
     * <p>예전에는 "TIMEOUT 이 아니면 HTTP_5XX" 로 읽었다. 모드가 둘뿐일 때는 맞았지만 MIXED 가 생겨 이름을 그대로 읽는다.
     * 못 읽으면 HTTP_5XX 로 본다 — 설정 응답에 모드가 없을 일은 없고, 있다면 보고서의 설정 전문에 드러난다.
     */
    static String readFailureMode(String configBody) {
        var matcher = java.util.regex.Pattern.compile("\"failureMode\"\\s*:\\s*\"(\\w+)\"").matcher(configBody);
        return matcher.find() ? matcher.group(1) : "HTTP_5XX";
    }

    /** 의존성을 늘리지 않으려고 숫자 하나만 긁는다. 보고서에는 설정 전문도 함께 남는다. */
    private static int readNumber(String configBody, String field) {
        var matcher = java.util.regex.Pattern.compile("\"" + field + "\"\\s*:\\s*(\\d+)")
                .matcher(configBody);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    /** 소수 하나를 긁는다. 필드가 없으면 기본값이다. */
    private static double readDecimal(String configBody, String field, double absent) {
        // 아주 작은 값은 지수 표기(5.0E-4)로 온다. 숫자 부분만 긁으면 5.0 으로 읽힌다
        var matcher = java.util.regex.Pattern.compile("\"" + field + "\"\\s*:\\s*([0-9.]+(?:[Ee][+-]?[0-9]+)?)")
                .matcher(configBody);
        return matcher.find() ? Double.parseDouble(matcher.group(1)) : absent;
    }
}
