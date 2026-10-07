package com.grandis.nova.mockapi.control.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.grandis.nova.mockapi.global.chaos.DefaultFailureInjector;
import com.grandis.nova.mockapi.global.chaos.FailureMode;
import com.grandis.nova.mockapi.global.chaos.LatencyTail;
import com.grandis.nova.mockapi.global.chaos.MixedResponse;
import com.grandis.nova.mockapi.global.chaos.MockConfigStore;
import com.grandis.nova.mockapi.global.config.MockProperties;
import com.grandis.nova.mockapi.registration.RegisterBodies;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * MIXED 의 실패 응답이 <b>실제 소켓에서</b> 어떤 모양으로 나가는지 본다.
 *
 * <p>MockMvc 로는 Content-Type 을 볼 수 없다. 스프링이 예외를 처리할 때 Content-Type 헤더를 지우는데, 실제 Tomcat 은
 * 이미 보낸 응답이라 무시하고 MockMvc 의 가짜 응답만 지워진다. 워커가 받는 것은 실제 소켓의 응답이다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MixedFailureSocketTest {

    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @LocalServerPort
    private int port;

    @Autowired
    private MockConfigStore store;

    @Autowired
    private MockProperties properties;

    @AfterEach
    void restoreDefaults() {
        store.update(properties.registerLatencyMs(), properties.failureRate(), properties.failureMode());
    }

    private HttpResponse<String> registerWith(MixedResponse kind) throws Exception {
        store.update(0, LatencyTail.NONE, 1.0, FailureMode.MIXED, List.of(kind));
        String key = UUID.randomUUID().toString();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/external/reservations"))
                .header("Idempotency-Key", key)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(RegisterBodies.of(key)))
                .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** 실제 장비는 Accept 를 보지 않는다. JSON 만 받는다고 해도 HTML 이 온다. */
    @Test
    @DisplayName("502 · 503 · 504 — Accept 가 JSON 이어도 text/html 본문이 그대로 나간다")
    void gatewayResponsesAreHtmlOnTheWire() throws Exception {
        for (MixedResponse kind : List.of(MixedResponse.HTTP_502, MixedResponse.HTTP_503, MixedResponse.HTTP_504)) {
            HttpResponse<String> response = registerWith(kind);

            assertThat(response.statusCode()).isEqualTo(kind.status());
            assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                    type -> assertThat(type).startsWith("text/html"));
            assertThat(response.body()).isEqualTo(kind.html());
            assertThat(response.headers().firstValue(DefaultFailureInjector.INJECTED_FAILURE_HEADER))
                    .contains(kind.name());
        }
    }

    @Test
    @DisplayName("본문 없는 500 — 길이 0, Content-Type 없음")
    void emptyFiveHundredOnTheWire() throws Exception {
        HttpResponse<String> response = registerWith(MixedResponse.HTTP_500_NO_BODY);

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).isEmpty();
        assertThat(response.headers().firstValue("Content-Type")).isEmpty();
        assertThat(response.headers().firstValue(DefaultFailureInjector.INJECTED_FAILURE_HEADER))
                .contains("HTTP_500_NO_BODY");
    }

    @Test
    @DisplayName("errorCode 있는 500 — Mock 의 JSON 오류 형식 그대로")
    void jsonFiveHundredOnTheWire() throws Exception {
        HttpResponse<String> response = registerWith(MixedResponse.HTTP_500);

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/json"));
        assertThat(response.body()).contains("\"errorCode\":\"UPSTREAM_UNAVAILABLE\"");
    }
}
