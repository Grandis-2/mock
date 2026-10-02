package com.grandis.nova.mockapi.control.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grandis.nova.mockapi.global.chaos.InMemoryFaultStore;
import com.grandis.nova.mockapi.registration.domain.Registration;
import com.grandis.nova.mockapi.registration.domain.RegistrationRepository;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * 결함 API 로 건 응답 유실이 <b>실제 소켓에서</b> 워커에게 결과 불명으로 보이는지 확인한다(외부 리뷰 M5).
 *
 * <p>두 시험이 반씩만 본다. {@code ConnectionDropperE2eTest} 는 실제 소켓이지만 시험 전용 경로의 {@code TIMEOUT}
 * 만 보고, {@code FaultFlowTest} 는 실제 등록 · 결함이지만 MockMvc 라 소켓이 없다(붙잡힌 응답이 빈 500 으로
 * 보인다). "실제 등록 + 결함" 을 실제 클라이언트로 본 것은 수동 확인뿐이었다. 여기서 잇는다.
 *
 * <p>워커가 겪는 순서 그대로 본다 — 읽기 타임아웃(결과 불명) → 그사이 원장에는 커밋됨 → 같은 키로 다시 보내면
 * 같은 번호가 재생된다. 커밋 전에 끊거나, 헤더를 먼저 내보내 500 으로 보이거나, 재시도가 새 번호를 받으면
 * 같은 예약이 두 번 등록되는 시험을 본 서비스가 할 수 없게 된다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // 유지 시간은 워커 타임아웃 + 2초 이상이어야 기동한다. 시험이 오래 걸리지 않게 둘 다 줄인다.
        properties = {"mock.timeout-hold-ms=3000", "mock.worker-read-timeout-ms=1000"})
class FaultSocketE2eTest {

    private static final String KEY = "socket-lost-1";
    private static final String BODY = """
            {"customerId":1001,"productId":12,"sku":"SM-G999-256-BLK"}""";

    /** 워커 읽기 타임아웃 자리. 유지 시간(3초)보다 짧아야 워커가 겪는 결과 불명이 된다. */
    private static final Duration WORKER_READ_TIMEOUT = Duration.ofSeconds(1);

    @LocalServerPort
    private int port;

    @Autowired
    private RegistrationRepository repository;

    @Autowired
    private InMemoryFaultStore faults;

    @BeforeEach
    void emptyEverything() {
        repository.deleteAll();
        faults.clear();
    }

    private RestClient client(Duration readTimeout) {
        // JDK HttpClient 로 본다. HttpURLConnection(SimpleClientHttpRequestFactory)은 toEntity 에서 읽기 타임아웃을
        // 일반 RestClientException 으로 감싸 "응답을 받았다" 와 구분이 안 된다(원시 소켓으로는 0바이트 수신 확인).
        var factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(readTimeout);
        return RestClient.builder()
                .requestFactory(factory)
                .baseUrl("http://localhost:" + port)
                .build();
    }

    private ResponseEntity<String> register(RestClient client) {
        return client.post().uri("/external/reservations")
                .header("Idempotency-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BODY)
                .retrieve()
                .toEntity(String.class);
    }

    @Test
    @DisplayName("결함을 건 키의 등록 — 워커는 읽기 타임아웃, 원장에는 커밋, 재시도는 같은 번호를 재생")
    void lostResponseIsUnknownThenReplays() {
        client(Duration.ofSeconds(5)).post().uri("/external/faults")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"externalKey":"socket-lost-1","faultType":"RESPONSE_LOST_AFTER_COMMIT"}""")
                .retrieve()
                .toBodilessEntity();

        RestClient worker = client(WORKER_READ_TIMEOUT);
        assertThatThrownBy(() -> register(worker))
                // 상태 줄을 받았다면(HttpServerErrorException) 워커는 일시 실패로 읽고 by-key 확인 없이 다시 보낸다
                .isInstanceOf(ResourceAccessException.class)
                .isNotInstanceOf(HttpServerErrorException.class);

        // 응답은 없었지만 등록은 커밋돼 있다. 결함은 커밋 뒤에 끊으므로 읽기 타임아웃 시점에 이미 보여야 한다
        Registration committed = repository.findById(KEY).orElseThrow();
        assertThat(committed.isActive()).isTrue();

        // 첫 요청은 아직 연결을 붙잡고 있다(유지 3초). 그래도 재시도는 막히지 않고 저장된 성공을 재생한다
        ResponseEntity<String> retry = register(worker);
        assertThat(retry.getStatusCode().value()).isEqualTo(201);
        assertThat(retry.getHeaders().getFirst("X-Idempotent-Replay")).isEqualTo("true");
        assertThat(retry.getBody()).contains(committed.externalNumber());
        assertThat(repository.count()).isEqualTo(1);
    }
}
