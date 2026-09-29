package com.grandis.nova.mockapi.load;

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
 * <p>시나리오는 세 가지다.
 * <ul>
 *   <li>{@code baseline} — 기본 설정 그대로. 평소 부하
 *   <li>{@code latency} — 지연 평균 1500ms · 실패율 0. <b>관측 지연이 설정값과 비슷한지</b> 본다.
 *       크게 벗어나면 Mock 이 병목이라는 뜻이고, 그러면 본 서비스 측정도 믿을 수 없다.
 *       <br>2000ms 였는데 지연이 평균으로 바뀌며(NV-121) 1500 으로 내렸다. 2000 이면 주입한 지연이
 *       2800ms 까지 뽑혀 오버헤드가 조금만 붙어도 워커 타임아웃(3초)을 넘는다 — Mock 탓이 아닌데
 *       결과 불명이 된다
 *   <li>{@code timeout} — 실패율 1.0 · TIMEOUT. 응답 없는 연결이 쌓이는지 본다
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
     * <p>Mock 은 대조용 목록 API 를 두지 않으므로(요구사항 2.3 · 팀 결정) 스키마를 읽기 전용으로
     * 조회한다.
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

    public static void main(String[] args) throws Exception {
        String baseUrl = args.length > 0 ? args[0] : DEFAULT_BASE_URL;
        String scenario = args.length > 1 ? args[1] : "baseline";
        String pass = args.length > 2 ? args[2] : "classify";
        int requests = args.length > 3 ? Integer.parseInt(args[3]) : AGREED_REQUESTS;
        String jdbcUrl = args.length > 4 ? args[4] : DEFAULT_JDBC_URL;
        String dbUser = args.length > 5 ? args[5] : DEFAULT_DB_USER;
        String dbPassword = args.length > 6 ? args[6] : DEFAULT_DB_PASSWORD;

        applyScenario(baseUrl, scenario);
        String configBody = get(baseUrl + CONFIG);
        int configVersion = readConfigVersion(configBody);
        // 지연은 평균값이라 요청마다 흔들린다. 흔드는 폭을 알아야 관측값에서 주입한 몫을 뺄 수 있다.
        // 평균화 이전 Mock 은 이 필드가 없으므로 0(고정)으로 본다.
        InjectedLatency injected = new InjectedLatency(
                readNumber(configBody, "registerLatencyMs"),
                readDecimal(configBody, "latencyJitter", 0.0));
        // TIMEOUT 모드에서 주사위에 걸린 요청은 Mock 이 응답 없이 붙잡는다. 그만큼은 클라이언트가
        // 포기할 때까지 떠 있으므로 동시 요청 상한을 계산할 때 따로 센다.
        double heldFraction = configBody.contains("\"failureMode\":\"TIMEOUT\"")
                ? readDecimal(configBody, "failureRate", 0.0)
                : 0.0;

        LoadPlan plan = switch (pass) {
            case "classify" -> LoadPlan.classify(baseUrl, requests);
            case "latency" -> LoadPlan.latency(baseUrl, requests);
            default -> throw new IllegalArgumentException(
                    "패스는 classify 또는 latency 여야 합니다. 받은 값: " + pass);
        };
        System.out.printf("시나리오 %s · 패스 %s · %d건 / %s · 타임아웃 %s · configVersion %d%n",
                scenario, plan.pass(), plan.totalRequests(), plan.rampUp(),
                plan.responseTimeout(), configVersion);
        System.out.println("주입한 지연: " + injected.describe());
        if (!plan.leavesRoomFor(injected)) {
            // 쏘기 전에 알린다. 5,000건을 다 쏘고 나서야 알면 실행 하나를 버린다.
            System.out.printf("⚠ 주입 최대 %dms + 허용 오버헤드 %dms 가 응답 타임아웃 %dms 를 넘는다. "
                            + "꼬리의 요청은 Mock 이 빨라도 결과 불명이 된다.%n",
                    injected.percentileMs(100), plan.maxP99Overhead().toMillis(),
                    plan.responseTimeout().toMillis());
        }
        System.out.println("설정: " + configBody);

        LoadRunner runner = new LoadRunner(plan);
        Duration elapsed = runner.run();

        // 클라이언트가 멈춰도 서버는 계속 처리한다. 잦아들기를 기다린 뒤 원장을 떠 온다.
        System.out.println("등록 원장이 잦아들기를 기다린다... (" + jdbcUrl + ")");
        RegistrationSnapshot db = RegistrationSnapshot.take(jdbcUrl, dbUser, dbPassword);

        String report = runner.report()
                .render(plan, configBody, configVersion, injected, heldFraction,
                        runner.maxInFlight(), runner.maxLaunchLag(), elapsed, db);
        System.out.println();
        System.out.println(report);

        Path out = Path.of("build", "load",
                "report-" + scenario + "-"
                        + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                        + ".md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report);
        System.out.println("보고서: " + out.toAbsolutePath());
    }

    /**
     * 시나리오에 맞게 Mock 설정을 바꾼다.
     *
     * <p>설정 경로에는 지연·실패를 주입하지 않으므로 실패율 1.0 상태에서도 되돌릴 수 있다.
     */
    private static void applyScenario(String baseUrl, String scenario) throws Exception {
        String body = switch (scenario) {
            case "baseline" -> """
                    {"registerLatencyMs":500,"failureRate":0.05,"failureMode":"HTTP_5XX"}""";
            case "latency" -> """
                    {"registerLatencyMs":1500,"failureRate":0.0,"failureMode":"HTTP_5XX"}""";
            case "timeout" -> """
                    {"registerLatencyMs":0,"failureRate":1.0,"failureMode":"TIMEOUT"}""";
            default -> throw new IllegalArgumentException(
                    "시나리오는 baseline · latency · timeout 중 하나여야 합니다. 받은 값: " + scenario);
        };

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + CONFIG))
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

    /** 의존성을 늘리지 않으려고 숫자 하나만 긁는다. 보고서에는 설정 전문도 함께 남는다. */
    private static int readNumber(String configBody, String field) {
        var matcher = java.util.regex.Pattern.compile("\"" + field + "\"\\s*:\\s*(\\d+)")
                .matcher(configBody);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    /** 소수 하나를 긁는다. 필드가 없으면 기본값이다. */
    private static double readDecimal(String configBody, String field, double absent) {
        var matcher = java.util.regex.Pattern.compile("\"" + field + "\"\\s*:\\s*([0-9.]+)")
                .matcher(configBody);
        return matcher.find() ? Double.parseDouble(matcher.group(1)) : absent;
    }
}
