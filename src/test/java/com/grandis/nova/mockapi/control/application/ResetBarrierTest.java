package com.grandis.nova.mockapi.control.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grandis.nova.mockapi.global.error.ErrorCode;
import com.grandis.nova.mockapi.global.error.MockException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 장벽 자체의 규칙. 등록 · 취소끼리는 막지 않고, 초기화와는 서로 막는다.
 *
 * <p>잠금은 잡은 스레드에서 풀어야 하므로, 다른 스레드에서 진행 중인 요청을 흉내 낼 때는 그 스레드
 * 안에서 {@code enter} · {@code exit} 를 짝 맞춰 부른다.
 */
class ResetBarrierTest {

    private final ResetBarrier barrier = new ResetBarrier();

    @Test
    @DisplayName("진행 중인 요청이 없으면 초기화를 실행하고 그 결과를 돌려준다")
    void runsWhenIdle() {
        assertThat(barrier.exclusively(() -> 7L)).isEqualTo(7L);
    }

    @Test
    @DisplayName("등록 · 취소끼리는 서로 막지 않는다")
    void requestsDoNotBlockEachOther() {
        barrier.enter();
        barrier.enter();
        assertThat(barrier.inFlight()).isEqualTo(2);
        barrier.exit();
        barrier.exit();
        assertThat(barrier.inFlight()).isZero();
    }

    @Test
    @DisplayName("다른 스레드에 진행 중인 요청이 있으면 초기화는 기다리지 않고 RESET_BUSY 로 거절한다")
    void refusesWhileInFlight() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> request = pool.submit(() -> {
                barrier.enter();
                try {
                    entered.countDown();
                    release.await();
                } finally {
                    barrier.exit();
                }
                return null;
            });
            entered.await();

            var ran = new boolean[1];
            assertThatThrownBy(() -> barrier.exclusively(() -> {
                ran[0] = true;
                return 0L;
            }))
                    .isInstanceOf(MockException.class)
                    .satisfies(e -> assertThat(((MockException) e).errorCode()).isEqualTo(ErrorCode.RESET_BUSY))
                    .hasMessageContaining("1건");
            assertThat(ran[0]).as("거절했으면 지우지 않는다").isFalse();

            release.countDown();
            request.get(5, TimeUnit.SECONDS);
        }
        assertThat(barrier.exclusively(() -> 1L)).isEqualTo(1L);
    }

    /** 초기화가 지우는 동안 들어온 요청이 그 전에 끼어들면, 세는 문장과 지우는 문장 사이에 커밋될 수 있다. */
    @Test
    @DisplayName("초기화가 진행 중이면 새 요청은 끝날 때까지 기다렸다가 진행한다")
    void requestWaitsForReset() throws Exception {
        var resetting = new CountDownLatch(1);
        var finishReset = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Long> reset = pool.submit(() -> barrier.exclusively(() -> {
                resetting.countDown();
                try {
                    finishReset.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return 0L;
            }));
            resetting.await();

            Future<?> request = pool.submit(() -> {
                barrier.enter();
                barrier.exit();
                return null;
            });
            assertThatThrownBy(() -> request.get(200, TimeUnit.MILLISECONDS))
                    .as("초기화가 끝나기 전에 들어가면 안 된다")
                    .isInstanceOf(TimeoutException.class);

            finishReset.countDown();
            reset.get(5, TimeUnit.SECONDS);
            request.get(5, TimeUnit.SECONDS);
        }
    }
}
