package com.grandis.nova.mockapi.global.chaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grandis.nova.mockapi.global.config.MockProperties;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * TIMEOUT 모드가 워커에게 정말 <b>무응답</b>으로 보이는지 실제 HTTP 클라이언트로 확인한다.
 *
 * <p>MockMvc 로는 잡히지 않는 문제가 있다. 헤더를 먼저 내보내면 소켓에는 상태 줄이 이미 도착해,
 * RestClient 는 본문을 읽다 타임아웃이 나도 그것을 삼키고 "본문 없는 500" 으로 넘긴다. 워커에게
 * 500 은 "일시 실패" 라는 분명한 뜻이라 UNKNOWN 이 되지 않는다. curl 의 {@code -m} 은 전체 시간
 * 제한이라 이 차이가 보이지 않았다.
 *
 * <p>그래서 읽기 타임아웃을 가진 실제 클라이언트로 본다. 유지 시간보다 짧게 기다리면 읽기
 * 타임아웃(UNKNOWN), 길게 기다리면 500(일시 실패)이어야 한다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // 유지 시간은 워커 타임아웃 + 2초 이상이어야 기동한다. 시험이 오래 걸리지 않게 둘 다 줄인다.
        properties = {"mock.timeout-hold-ms=3000", "mock.worker-read-timeout-ms=1000"})
@Import(ConnectionDropperE2eTest.InjectProbeController.class)
class ConnectionDropperE2eTest {

    @LocalServerPort
    private int port;

    @Autowired
    private MockConfigStore store;

    @Autowired
    private MockProperties properties;

    private RestClient clientWithReadTimeout(Duration readTimeout) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setReadTimeout(readTimeout);
        return RestClient.builder()
                .requestFactory(factory)
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @BeforeEach
    void injectTimeout() {
        store.update(0, 1.0, FailureMode.TIMEOUT);
    }

    /** 설정·결함을 바꾼 시험은 스스로 되돌린다(팀 규칙). */
    @AfterEach
    void restoreDefaults() {
        store.update(properties.registerLatencyMs(), properties.failureRate(), properties.failureMode());
    }

    @Test
    @DisplayName("워커 읽기 타임아웃이 유지 시간보다 짧으면 읽기 타임아웃이다 — UNKNOWN")
    void looksLikeTimeoutToTheWorker() {
        var client = clientWithReadTimeout(Duration.ofSeconds(1));

        assertThatThrownBy(() -> client.get().uri("/probe/inject").retrieve().body(String.class))
                // 상태를 받았다면(HttpServerErrorException) 워커는 일시 실패로 처리한다.
                .isInstanceOf(ResourceAccessException.class);
    }

    @Test
    @DisplayName("끝까지 기다리면 500 이다 — 성공으로 오인되지 않는다")
    void fallsBackToTransientFailure() {
        var client = clientWithReadTimeout(Duration.ofSeconds(10));

        assertThatThrownBy(() -> client.get().uri("/probe/inject").retrieve().body(String.class))
                .isInstanceOf(HttpServerErrorException.class)
                .satisfies(e -> assertThat(((HttpServerErrorException) e).getStatusCode().value()).isEqualTo(500));
    }

    /** 등록 파트가 처리를 시작할 때와 같은 순서로 부른다. */
    @RestController
    static class InjectProbeController {

        private final ConfigProvider configProvider;
        private final FailureInjector failureInjector;

        InjectProbeController(ConfigProvider configProvider, FailureInjector failureInjector) {
            this.configProvider = configProvider;
            this.failureInjector = failureInjector;
        }

        @GetMapping("/probe/inject")
        String inject() {
            failureInjector.apply(configProvider.snapshot());
            return "통과";
        }
    }
}
