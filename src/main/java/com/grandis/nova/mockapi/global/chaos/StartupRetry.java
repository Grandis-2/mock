package com.grandis.nova.mockapi.global.chaos;

import java.time.Duration;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;

/**
 * 기동할 때 Redis 첫 연결만 다시 시도한다({@link RedisConfigSync#start}).
 *
 * <p>Lettuce 는 연결을 만드는 순간부터 명령 시간 초과({@code spring.data.redis.timeout}, 운영 500ms)를 잰다. 그 안에 DNS ·
 * TCP · TLS · HELLO 가 끝나야 하는데, 막 뜬 JVM 은 Netty · TLS 클래스를 처음 읽는 시간까지 들어가 느린 CPU(운영 Fargate
 * 1 vCPU · OTel 에이전트)에서는 첫 연결이 매번 넘긴다. 두 번째부터는 클래스가 읽혀 있어 빠르다 — 그래서 같은 프로세스 안에서
 * 다시 시도한다. ECS 가 태스크를 다시 띄우면 새 JVM 이 또 처음부터 느려 같은 실패가 되풀이된다.
 *
 * <p>운영 중 시간 초과는 그대로다(등록 경로의 보장, {@code RedisTimeoutGuard}). 다시 시도하는 것은 Redis 에 닿지 못한 것
 * (연결 실패 · 시간 초과)뿐이고, 상한({@link #BUDGET})을 넘기면 마지막 오류를 그대로 던져 지금처럼 뜨지 않는다 — Redis 가
 * 정말 죽어 있으면 뜨지 않아야 한다. 상한은 인프라 컨테이너 헬스 체크의 기동 유예(60초)에서 운영 기동(약 29초)을 뺀 만큼이다.
 */
final class StartupRetry {

    static final Duration BUDGET = Duration.ofSeconds(30);
    static final Duration FIRST_DELAY = Duration.ofMillis(500);

    private static final Logger log = LoggerFactory.getLogger(StartupRetry.class);

    /** 시험이 기다리지 않게 바꿔 끼운다. */
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    static final StartupRetry DEFAULT = new StartupRetry(BUDGET, FIRST_DELAY,
            duration -> Thread.sleep(duration), System::nanoTime);

    private final Duration budget;
    private final Duration firstDelay;
    private final Sleeper sleeper;
    private final LongSupplier nanoTime;

    StartupRetry(Duration budget, Duration firstDelay, Sleeper sleeper, LongSupplier nanoTime) {
        this.budget = budget;
        this.firstDelay = firstDelay;
        this.sleeper = sleeper;
        this.nanoTime = nanoTime;
    }

    /** 성공할 때까지 0.5s → 1s → 2s … 쉬며 다시 시도한다. 쉬는 시간은 남은 상한을 넘지 않는다. */
    <T> T run(String what, Supplier<T> attempt) {
        long deadline = nanoTime.getAsLong() + budget.toNanos();
        Duration delay = firstDelay;
        for (int tried = 1; ; tried++) {
            try {
                return attempt.get();
            } catch (RedisConnectionFailureException | QueryTimeoutException e) {
                Duration left = Duration.ofNanos(deadline - nanoTime.getAsLong());
                if (left.isNegative() || left.isZero()) {
                    log.error("{} 실패 — {}번 시도했고 상한 {}초를 넘겨 기동을 멈춘다", what, tried, budget.toSeconds());
                    throw e;
                }
                Duration wait = delay.compareTo(left) < 0 ? delay : left;
                log.warn("{} 실패({}번째) — {}ms 뒤 다시 시도한다(남은 {}ms): {}",
                        what, tried, wait.toMillis(), left.toMillis(), rootCause(e));
                sleep(wait, e);
                delay = delay.multipliedBy(2);
            }
        }
    }

    private void sleep(Duration wait, RuntimeException cause) {
        try {
            sleeper.sleep(wait);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            cause.addSuppressed(interrupted);
            throw cause;
        }
    }

    private static String rootCause(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.toString();
    }
}
