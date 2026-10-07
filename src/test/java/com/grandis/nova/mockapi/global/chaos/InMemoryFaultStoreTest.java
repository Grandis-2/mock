package com.grandis.nova.mockapi.global.chaos;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 결함 보관소의 규칙을 본다. 스프링 없이 본다 — 동작이 맵 하나에 들어 있어 컨텍스트가 필요 없다.
 *
 * <p>여기서 보는 것은 <b>한 번만 발동한다</b>와 <b>키끼리 섞이지 않는다</b>다. 둘 다 틀려도 오류가
 * 나지 않고 시험이 조용히 잘못된 것을 증명하게 되는 종류다 — 결함이 두 번 발동하면 재시도마저
 * 응답을 잃어 "재생 확인" 이 영원히 안 되고, 키가 섞이면 결함을 걸지 않은 요청이 응답을 잃는다.
 */
@ExtendWith(OutputCaptureExtension.class)
class InMemoryFaultStoreTest {

    private static final String KEY = "test-lost-1";
    private static final String OTHER_KEY = "test-lost-2";

    private InMemoryFaultStore store;

    @BeforeEach
    void freshStore() {
        store = new InMemoryFaultStore();
    }

    @Test
    @DisplayName("걸지 않은 키는 발동하지 않는다")
    void notInjected() {
        assertThat(store.consumeResponseLost(KEY)).isFalse();
    }

    /** be 로그와 키로 맞대 보려고 남긴다. 발동하지 않은 조회(다른 키 · 다른 종류)는 남기지 않는다. */
    @Test
    @DisplayName("결함이 발동하면 종류 · 키를 로그로 남긴다")
    void firedFaultIsLogged(CapturedOutput output) {
        store.inject(KEY, FaultType.RESPONSE_LOST_AFTER_COMMIT);
        store.consumeResponseLost(OTHER_KEY);
        assertThat(output).doesNotContain("결함 발동");

        store.consumeResponseLost(KEY);
        assertThat(output).contains("결함 발동 RESPONSE_LOST_AFTER_COMMIT key=" + KEY);
        // 기다리는 시간은 느린 성공에만 있다. 응답 유실에 delayMs=0 을 찍으면 "0ms 기다렸다" 로 읽힌다
        assertThat(output).doesNotContain("delayMs");

        store.inject(OTHER_KEY, FaultType.SLOW_SUCCESS, 1);
        store.holdBeforeCommit(OTHER_KEY);
        assertThat(output).contains("결함 발동 SLOW_SUCCESS key=" + OTHER_KEY + " delayMs=1");
    }

    @Test
    @DisplayName("한 번 발동하면 자동으로 해제된다")
    void consumedOnce() {
        store.inject(KEY, FaultType.RESPONSE_LOST_AFTER_COMMIT);

        assertThat(store.consumeResponseLost(KEY)).isTrue();
        assertThat(store.consumeResponseLost(KEY)).isFalse();
    }

    @Test
    @DisplayName("다른 키에는 영향을 주지 않는다")
    void isolatedByKey() {
        store.inject(KEY, FaultType.RESPONSE_LOST_AFTER_COMMIT);

        assertThat(store.consumeResponseLost(OTHER_KEY)).isFalse();
        // 남의 키를 물어본 것이 내 결함을 소비하지 않았다
        assertThat(store.consumeResponseLost(KEY)).isTrue();
    }

    @Test
    @DisplayName("같은 키에 다시 걸면 새 결함으로 바뀌고, 그래도 한 번만 발동한다")
    void reinjectReplaces() {
        store.inject(KEY, FaultType.RESPONSE_LOST_AFTER_COMMIT);
        store.inject(KEY, FaultType.RESPONSE_LOST_AFTER_COMMIT);

        assertThat(store.consumeResponseLost(KEY)).isTrue();
        assertThat(store.consumeResponseLost(KEY)).isFalse();
    }

    @Test
    @DisplayName("초기화하면 발동하지 않은 결함이 사라진다")
    void clearDropsPendingFaults() {
        store.inject(KEY, FaultType.RESPONSE_LOST_AFTER_COMMIT);
        store.inject(OTHER_KEY, FaultType.RESPONSE_LOST_AFTER_COMMIT);

        store.clear();

        assertThat(store.consumeResponseLost(KEY)).isFalse();
        assertThat(store.consumeResponseLost(OTHER_KEY)).isFalse();
    }

    // ---------------------------------------------------------------- 느린 성공 (NV-261)

    @Test
    @DisplayName("느린 성공 — 걸린 키는 정한 시간을 기다리고, 한 번 발동하면 풀린다")
    void slowSuccessHoldsOnce() {
        store.inject(KEY, FaultType.SLOW_SUCCESS, 50);

        assertThat(store.holdBeforeCommit(KEY)).isEqualTo(50);
        assertThat(store.holdBeforeCommit(KEY)).isZero();
        assertThat(store.holdBeforeCommit(OTHER_KEY)).isZero();
    }

    /** 종류가 섞이면 응답 유실을 건 키가 커밋 전에 기다리거나, 느린 성공이 응답을 잃는다. */
    @Test
    @DisplayName("느린 성공과 응답 유실은 서로를 꺼내지 않는다")
    void kindsDoNotConsumeEachOther() {
        store.inject(KEY, FaultType.SLOW_SUCCESS, 50);
        store.inject(OTHER_KEY, FaultType.RESPONSE_LOST_AFTER_COMMIT);

        assertThat(store.consumeResponseLost(KEY)).isFalse();
        assertThat(store.holdBeforeCommit(OTHER_KEY)).isZero();

        assertThat(store.holdBeforeCommit(KEY)).isEqualTo(50);
        assertThat(store.consumeResponseLost(OTHER_KEY)).isTrue();
    }

    /**
     * 기다린 뒤에 풀면 그사이 들어온 같은 키 재시도도 결함을 보고 같이 기다려, 시연의 "404 → 같은 키 재시도 → 재시도가
     * 먼저 커밋" 이 안 보인다. 첫 요청이 기다리는 동안 결함은 이미 없어야 한다.
     *
     * <p>고정 sleep 으로 순서를 맞추지 않는다. 첫 요청이 결함을 꺼낼 때까지(보관소에서 사라질 때까지) 기다린다.
     */
    @Test
    @DisplayName("느린 성공 — 결함은 기다리기 전에 꺼낸다. 기다리는 동안 같은 키는 기다리지 않는다")
    void takenBeforeWaiting() throws Exception {
        store.inject(KEY, FaultType.SLOW_SUCCESS, 1_000);

        CompletableFuture<Long> first = CompletableFuture.supplyAsync(() -> store.holdBeforeCommit(KEY));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (store.pending(KEY) && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(store.pending(KEY)).as("첫 요청이 결함을 꺼냈다").isFalse();
        assertThat(first).as("첫 요청은 아직 기다리는 중").isNotDone();

        long started = System.nanoTime();
        assertThat(store.holdBeforeCommit(KEY)).as("같은 키 재시도는 기다리지 않는다").isZero();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(500));

        assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(1_000);
    }

    /** 더하지 않으면 그 대기가 부하 판정에서 "Mock 의 오버헤드" 로 잡힌다. */
    @Test
    @DisplayName("느린 성공 — 기다린 시간을 주입 지연 헤더에 더한다")
    void addsWaitToInjectedLatencyHeader() {
        var response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest(), response));
        try {
            // 지연 주입이 먼저 120ms 를 실었다
            response.setHeader(DefaultFailureInjector.INJECTED_LATENCY_HEADER, "120");
            store.inject(KEY, FaultType.SLOW_SUCCESS, 30);

            store.holdBeforeCommit(KEY);

            assertThat(response.getHeader(DefaultFailureInjector.INJECTED_LATENCY_HEADER)).isEqualTo("150");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }
}
