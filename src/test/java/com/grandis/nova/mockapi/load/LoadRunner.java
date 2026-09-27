package com.grandis.nova.mockapi.load;

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

    private final LoadPlan plan;
    private final HttpClient client;
    private final LoadReport report = new LoadReport();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();

    public LoadRunner(LoadPlan plan) {
        this.plan = plan;
        this.client = HttpClient.newBuilder()
                // 연결 자체가 안 되는 것과 응답이 없는 것을 구분해야 미전송과 결과 불명이 갈린다.
                .connectTimeout(Duration.ofSeconds(2))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }

    public LoadReport report() {
        return report;
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
                sent.add(launcher.submit(() -> sendAt(startedAt, dueNanos)));
            }
            awaitAll(sent);
        }
        return Duration.between(startedAt, Instant.now());
    }

    /** 계획한 시각까지 기다렸다가 보낸다. 늦었으면 바로 보낸다. */
    private void sendAt(Instant startedAt, long dueNanos) {
        long waitNanos = dueNanos - Duration.between(startedAt, Instant.now()).toNanos();
        if (waitNanos > 0) {
            try {
                Thread.sleep(Duration.ofNanos(waitNanos));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                report.add(new LoadReport.Attempt(Outcome.NOT_SENT, 0, Duration.ZERO));
                return;
            }
        }
        send();
    }

    private void send() {
        HttpRequest request = HttpRequest.newBuilder(URI.create(plan.baseUrl() + RESERVATIONS))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .timeout(plan.responseTimeout())
                .POST(HttpRequest.BodyPublishers.ofString(BODY))
                .build();

        trackEnter();
        Instant sentAt = Instant.now();
        try {
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            report.add(new LoadReport.Attempt(
                    Outcome.ofStatus(response.statusCode()),
                    response.statusCode(),
                    Duration.between(sentAt, Instant.now())));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            report.add(new LoadReport.Attempt(Outcome.UNKNOWN, 0, Duration.between(sentAt, Instant.now())));
        } catch (Exception e) {
            report.add(new LoadReport.Attempt(
                    Outcome.ofFailure(e), 0, Duration.between(sentAt, Instant.now())));
        } finally {
            inFlight.decrementAndGet();
        }
    }

    private void trackEnter() {
        int now = inFlight.incrementAndGet();
        maxInFlight.updateAndGet(previous -> Math.max(previous, now));
    }

    private void awaitAll(List<Future<?>> sent) {
        Instant deadline = Instant.now().plus(plan.rampUp()).plus(plan.drainTimeout());
        for (Future<?> future : sent) {
            long left = Duration.between(Instant.now(), deadline).toMillis();
            try {
                future.get(Math.max(left, 0), java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // 관찰 종료 조건을 넘긴 요청. 버리지 않고 결과 불명으로 센다.
                future.cancel(true);
                report.add(new LoadReport.Attempt(Outcome.UNKNOWN, 0, plan.responseTimeout()));
            }
        }
    }
}
