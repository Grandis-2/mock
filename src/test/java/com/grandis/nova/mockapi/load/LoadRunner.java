package com.grandis.nova.mockapi.load;

import com.grandis.nova.mockapi.global.chaos.DefaultFailureInjector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 계획대로 등록 요청을 보내고 결과를 모은다.
 *
 * <p>가상 스레드로 보낸다. 요청 하나에 플랫폼 스레드를 하나씩 쓰면 5,000건을 동시에 띄울 수 없고,
 * 스레드 풀로 줄이면 <b>클라이언트가 병목이 되어</b> 무엇을 측정한 것인지 알 수 없게 된다.
 *
 * <p>발사 시각을 계획이 정한 간격으로 고르게 잡는다. 한 번에 쏟으면 클라이언트 쪽 커넥션 생성이
 * 몰려 서버가 아니라 그쪽이 먼저 막힌다.
 *
 * <p>멱등 키는 요청마다 새로 만든다. 같은 키를 쓰면 두 번째부터는 재생이라 DB 쓰기가 일어나지 않아
 * 부하가 아니게 된다.
 */
public final class LoadRunner {

    private static final String RESERVATIONS = "/external/reservations";
    private static final String BODY = """
            {"customerId":1001,"productId":12,"sku":"SM-G999-256-BLK"}""";

    private static final java.util.regex.Pattern EXTERNAL_NUMBER =
            java.util.regex.Pattern.compile("\"externalNumber\"\\s*:\\s*\"([^\"]+)\"");

    private final LoadPlan plan;
    private final HttpClient client;
    private final LoadReport report = new LoadReport();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();

    /** 계획한 발사 시각보다 얼마나 늦게 쐈는지의 최대치. 목표 부하를 만들었는지의 근거다. */
    private final java.util.concurrent.atomic.AtomicLong maxLaunchLagNanos =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * 요청 번호마다 쓸 멱등 키. <b>보내기 전에 미리 만들어 둔다.</b>
     *
     * <p>두 가지 때문이다. 첫째, 끝나고 DB 를 <b>키로</b> 대조해야 한다 — 건수만 맞춰 보면 201 키
     * 하나가 빠지고 5xx 키 하나가 들어가도 개수가 같아 최악의 버그를 놓친다(요구사항 8장
     * "건수 합계만 비교하지 않음"). 둘째, 보내는 자리에서 키를 만들면 관찰 종료를 넘겨 바깥에서
     * 결과를 적는 경로가 <b>어느 요청인지 말할 수 없다.</b>
     */
    private final String[] keys;

    public LoadRunner(LoadPlan plan) {
        this.plan = plan;
        this.client = HttpClient.newBuilder()
                // 연결 자체가 안 되는 것과 응답이 없는 것을 구분해야 미전송과 결과 불명이 갈린다.
                .connectTimeout(Duration.ofSeconds(2))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
        this.keys = new String[plan.totalRequests()];
        for (int i = 0; i < keys.length; i++) {
            // 키를 요청마다 새로 만든다. 같은 키를 쓰면 두 번째부터는 재생이라 DB 쓰기가 없어 부하가 아니다.
            keys[i] = UUID.randomUUID().toString();
        }
    }

    public LoadReport report() {
        return report;
    }

    public Duration maxLaunchLag() {
        return Duration.ofNanos(maxLaunchLagNanos.get());
    }

    public int maxInFlight() {
        return maxInFlight.get();
    }

    /**
     * 부하를 걸고 응답이 정리될 때까지 기다린다.
     *
     * <p>{@code drainTimeout} 안에 끝나지 않은 요청은 <b>결과 불명으로 센다.</b> 세지 않고 버리면
     * 보낸 요청 수와 네 분류의 합이 어긋나 요구사항 8장이 금지한 "실패를 결과에서 빼는 것" 이 된다.
     *
     * @return 첫 발사부터 마지막 응답까지 걸린 시간
     */
    public Duration run() {
        Instant startedAt = Instant.now();
        long intervalNanos = plan.launchInterval().toNanos();

        try (ExecutorService launcher = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> sent = new java.util.ArrayList<>(plan.totalRequests());
            for (int i = 0; i < plan.totalRequests(); i++) {
                long dueNanos = i * intervalNanos;
                String key = keys[i];
                sent.add(launcher.submit(() -> sendAt(startedAt, dueNanos, key)));
            }
            awaitAll(sent);
        }
        return Duration.between(startedAt, Instant.now());
    }

    /**
     * 계획한 시각까지 기다렸다가 보낸다. 늦었든 늦게 깨어났든 <b>예정보다 얼마나 늦게 보냈는지 남긴다.</b>
     *
     * <p>이 시험은 5,000건을 10초에 고르게 쏘는 것이 전제다. 발사가 몇 초씩 밀렸다면 그 실행은
     * <b>목표 부하를 만들지 못한 것</b>이고, 요구사항 8장은 그런 시험을 성능 합격으로 판정하지
     * 않는다. 판정에서 뺄 근거가 사람의 관찰이 아니라 숫자여야 해서 여기서 기록한다.
     */
    private void sendAt(Instant startedAt, long dueNanos, String key) {
        long waitNanos = dueNanos - Duration.between(startedAt, Instant.now()).toNanos();
        if (waitNanos > 0) {
            try {
                Thread.sleep(Duration.ofNanos(waitNanos));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                report.add(new LoadReport.Attempt(key, null, Outcome.NOT_SENT, 0, Duration.ZERO));
                return;
            }
        }
        // 잠든 뒤에도 잰다. 예정 시각까지 잠들었다가 PC 가 멈칫해 몇 초 늦게 깨어나면, 처음부터 늦은 경우만
        // 보던 예전 방식으로는 기록되지 않았다(리뷰 H3 ②). 깨어난 지금이 예정보다 얼마나 늦었는지가 발사 지연이다.
        trackLag(Duration.between(startedAt, Instant.now()).toNanos() - dueNanos);
        send(key);
    }

    /** 음수(예정보다 일찍 깸)는 0 으로 본다. 최대치만 남긴다 — 한 번이라도 크게 밀렸으면 그 실행은 무효다. */
    void trackLag(long lagNanos) {
        long lag = Math.max(lagNanos, 0);
        maxLaunchLagNanos.updateAndGet(previous -> Math.max(previous, lag));
    }

    private void send(String key) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(plan.baseUrl() + RESERVATIONS))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", key)
                .timeout(plan.responseTimeout())
                .POST(HttpRequest.BodyPublishers.ofString(BODY))
                .build();

        trackEnter();
        Instant sentAt = Instant.now();
        try {
            // 본문을 버리지 않고 읽는다. 201 의 예약번호를 DB 에 적힌 번호와 맞춰 봐야 하기 때문이다.
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            Duration latency = Duration.between(sentAt, Instant.now());
            Outcome outcome = Outcome.ofStatus(response.statusCode());
            report.add(new LoadReport.Attempt(
                    key,
                    outcome == Outcome.ACCEPTED ? externalNumberOf(response.body()) : null,
                    outcome,
                    response.statusCode(),
                    latency,
                    injectedMsOf(response)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            report.add(new LoadReport.Attempt(
                    key, null, Outcome.UNKNOWN, 0, Duration.between(sentAt, Instant.now())));
        } catch (Exception e) {
            report.add(new LoadReport.Attempt(
                    key, null, Outcome.ofFailure(e), 0, Duration.between(sentAt, Instant.now())));
        } finally {
            inFlight.decrementAndGet();
        }
    }

    /**
     * 201 응답에서 예약번호만 긁는다. {@code LoadTestMain} 의 {@code configVersion} 과 같은 방식이다 —
     * 값 하나를 뽑는 데 JSON 의존성을 늘리지 않는다.
     *
     * <p>못 찾으면 null 이다. 등록 응답에는 반드시 있어야 하므로, null 이 남으면 그 자체가 결함이다.
     */
    private static String externalNumberOf(String body) {
        var matcher = EXTERNAL_NUMBER.matcher(body);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * 이 요청에 Mock 이 실제로 넣은 지연. 요청마다 오버헤드를 구하는 데 쓴다.
     *
     * <p>없으면 null 이다. 등록 응답에는 반드시 있어야 하므로(지연 0 이어도 {@code 0}), 남으면 보고서가
     * 그 건수를 경고로 적는다.
     */
    private static Long injectedMsOf(HttpResponse<?> response) {
        return response.headers().firstValue(DefaultFailureInjector.INJECTED_LATENCY_HEADER)
                .map(value -> {
                    try {
                        return Long.valueOf(value.trim());
                    } catch (NumberFormatException e) {
                        return null;
                    }
                })
                .orElse(null);
    }

    private void trackEnter() {
        int now = inFlight.incrementAndGet();
        maxInFlight.updateAndGet(previous -> Math.max(previous, now));
    }

    /**
     * 관찰 종료 조건까지 기다린다.
     *
     * <p>넘긴 요청에 {@code cancel(true)} 를 걸면 그 요청을 보내던 스레드도 인터럽트를 받아
     * {@link #send} 쪽에서 결과를 한 번 더 적는다. <b>키로 적기 때문에</b> 같은 요청이 두 번
     * 세어지지 않는다 — 두 번 세면 분류 합이 보낸 요청 수를 넘어, 합격 조건이 오판정된다.
     */
    private void awaitAll(List<Future<?>> sent) {
        Instant deadline = Instant.now().plus(plan.rampUp()).plus(plan.drainTimeout());
        for (int i = 0; i < sent.size(); i++) {
            Future<?> future = sent.get(i);
            long left = Duration.between(Instant.now(), deadline).toMillis();
            try {
                future.get(Math.max(left, 0), java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // 관찰 종료 조건을 넘긴 요청. 버리지 않고 결과 불명으로 센다.
                future.cancel(true);
                report.add(new LoadReport.Attempt(
                        keys[i], null, Outcome.UNKNOWN, 0, plan.responseTimeout()));
            }
        }
    }
}
