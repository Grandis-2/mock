package com.grandis.nova.mockapi.global.chaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;

/** 기동 재시도의 간격 · 상한 · 다시 시도하는 오류. 시계를 바꿔 끼워 실제로 기다리지 않는다. */
class StartupRetryTest {

    /** 시도 하나가 걸리는 시간. 운영의 명령 시간 초과(500ms)만큼 기다리다 실패한다고 본다. */
    private static final Duration ATTEMPT = Duration.ofMillis(500);

    private final AtomicLong now = new AtomicLong();
    private final List<Duration> waits = new ArrayList<>();
    private final StartupRetry retry = new StartupRetry(StartupRetry.BUDGET, StartupRetry.FIRST_DELAY,
            duration -> {
                waits.add(duration);
                now.addAndGet(duration.toNanos());
            },
            now::get);

    @Test
    @DisplayName("처음 몇 번 Redis 에 닿지 못해도 다시 시도해 붙으면 그 값을 돌려준다 — 0.5s → 1s → 2s")
    void retriesUntilConnected() {
        AtomicInteger tried = new AtomicInteger();

        String result = retry.run("시험", () -> {
            now.addAndGet(ATTEMPT.toNanos());
            int n = tried.incrementAndGet();
            if (n == 1) {
                throw new RedisConnectionFailureException("Connection initialization timed out after 500 millisecond(s)");
            }
            if (n <= 3) {
                throw new QueryTimeoutException("Redis command timed out");
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(tried).hasValue(4);
        assertThat(waits).containsExactly(Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("Redis 가 계속 없으면 상한(30초)을 넘긴 뒤 마지막 오류를 그대로 던진다")
    void givesUpAfterBudget() {
        AtomicInteger tried = new AtomicInteger();
        RedisConnectionFailureException last = new RedisConnectionFailureException("down");

        assertThatThrownBy(() -> retry.run("시험", () -> {
            now.addAndGet(ATTEMPT.toNanos());
            tried.incrementAndGet();
            throw last;
        })).isSameAs(last);

        // 쉬는 시간은 남은 상한을 넘지 않는다 — 마지막 시도는 상한에 맞춰 한 번 더 하고 끝난다.
        assertThat(Duration.ofNanos(now.get())).isBetween(StartupRetry.BUDGET, StartupRetry.BUDGET.plus(ATTEMPT));
        assertThat(waits.stream().reduce(Duration.ZERO, Duration::plus)).isLessThan(StartupRetry.BUDGET);
        assertThat(waits).startsWith(Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(2),
                Duration.ofSeconds(4), Duration.ofSeconds(8));
        assertThat(tried.get()).isBetween(6, 8);
    }

    @Test
    @DisplayName("Redis 에 닿지 못한 것이 아니면(명령 오류 · 버그) 다시 시도하지 않는다")
    void doesNotRetryOtherErrors() {
        AtomicInteger tried = new AtomicInteger();

        assertThatThrownBy(() -> retry.run("시험", () -> {
            tried.incrementAndGet();
            throw new RedisSystemException("ERR script error", new IllegalStateException());
        })).isInstanceOf(RedisSystemException.class);
        assertThatThrownBy(() -> retry.run("시험", () -> {
            tried.incrementAndGet();
            throw new IllegalStateException("bug");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(tried).hasValue(2);
        assertThat(waits).isEmpty();
    }

    @Test
    @DisplayName("기다리다 끊기면(종료 신호) 더 시도하지 않고 원래 오류를 던지며 끊김 표시를 남긴다")
    void stopsWhenInterrupted() {
        StartupRetry interrupted = new StartupRetry(StartupRetry.BUDGET, StartupRetry.FIRST_DELAY,
                duration -> {
                    throw new InterruptedException();
                },
                now::get);
        RedisConnectionFailureException down = new RedisConnectionFailureException("down");

        try {
            assertThatThrownBy(() -> interrupted.run("시험", () -> {
                throw down;
            })).isSameAs(down);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
