package com.grandis.nova.mockapi.global.chaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grandis.nova.mockapi.global.error.ErrorCode;
import com.grandis.nova.mockapi.global.error.MockException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 지연·실패 주입.
 *
 * <p>{@code TIMEOUT} 모드는 실제 서블릿 응답이 있어야 하므로 여기서 다루지 않는다. 가짜 응답으로는
 * "정말 아무것도 안 나갔는지" 를 증명할 수 없다. 등록 API 가 올라온 뒤 통합 시험(NV-19)에서
 * 워커가 UNKNOWN 을 겪고 by-key 로 확인하는 시나리오로 본다.
 */
@SpringBootTest
class DefaultFailureInjectorTest {

    @Autowired
    private FailureInjector injector;

    private static ConfigSnapshot config(int latencyMs, double failureRate) {
        return new ConfigSnapshot(latencyMs, failureRate, FailureMode.HTTP_5XX, 0);
    }

    /**
     * 스텁이 안 물러나면 아무 일도 하지 않는 구현이 계속 쓰인다. 지연도 실패도 일어나지 않는데
     * 오류는 나지 않아, 시연 직전에야 "왜 실패율이 안 먹지" 로 드러난다.
     */
    @Test
    @DisplayName("실제 구현이 올라오면 임시 스텁은 물러난다")
    void stubBacksOff() {
        assertThat(injector).isInstanceOf(DefaultFailureInjector.class);
    }

    @Test
    @DisplayName("실패율 0 이면 걸리지 않는다")
    void neverFailsAtZero() {
        for (int i = 0; i < 200; i++) {
            assertThatCode(() -> injector.apply(config(0, 0.0))).doesNotThrowAnyException();
        }
    }

    /**
     * 실패율 1.0 이 전부 실패해야 "저장된 실패를 재생하지 않는다" 를 시험할 수 있다. 5% 로만
     * 확인하면 일시 실패를 저장해 버리는 버그가 드러나지 않는다.
     */
    @Test
    @DisplayName("실패율 1 이면 전부 UPSTREAM_UNAVAILABLE 이다")
    void alwaysFailsAtOne() {
        for (int i = 0; i < 50; i++) {
            assertThatThrownBy(() -> injector.apply(config(0, 1.0)))
                    .isInstanceOf(MockException.class)
                    .extracting(e -> ((MockException) e).errorCode())
                    .isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
        }
    }

    @Test
    @DisplayName("설정한 지연만큼 실제로 기다린다")
    void waitsForConfiguredLatency() {
        long start = System.nanoTime();

        injector.apply(config(300, 0.0));

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs).isGreaterThanOrEqualTo(300);
    }

    /**
     * 이 클래스는 시험 설정에서 지터를 0 으로 두고 돈다. 그래야 "설정만큼 기다린다" 를 단정할 수
     * 있다. 그 전제가 실제로 성립하는지 본다 — 깨지면 위 시험들이 우연히 통과하게 된다.
     *
     * <p>흔드는 쪽은 {@link LatencyJitterTest} 가 본다.
     */
    @Test
    @DisplayName("시험 설정의 지터 0 에서는 설정값 그대로다")
    void zeroJitterIsFixed() {
        DefaultFailureInjector real = (DefaultFailureInjector) injector;

        for (int i = 0; i < 100; i++) {
            assertThat(real.jittered(500)).isEqualTo(500);
        }
    }
}
