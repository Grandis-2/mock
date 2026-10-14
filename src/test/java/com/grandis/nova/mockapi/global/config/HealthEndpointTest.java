package com.grandis.nova.mockapi.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

/**
 * 헬스 체크가 관리 포트로만 나가는지 실제 Tomcat 두 개(서비스 포트 · 관리 포트)에 HTTP 로 묻는다 — MockMvc 는 관리 포트의
 * 자식 컨텍스트를 띄우지 않아 이 경우를 못 본다. 관리 포트는 시험 설정이 0(빈 포트)으로 덮는다. 나머지는 MockDefaults 그대로다.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@DisplayName("헬스 체크 — 관리 포트의 readiness · liveness 는 200, 서비스 포트로는 나가지 않는다")
class HealthEndpointTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Value("${local.server.port}") int port;
    @Value("${local.management.port}") int managementPort;
    @Autowired HealthEndpointGroups groups;

    @Test
    @DisplayName("관리 포트의 readiness · liveness 는 200 UP")
    void probesAnswerOnTheManagementPort() throws Exception {
        assertThat(managementPort).isNotEqualTo(port);
        for (String path : new String[]{"/actuator/health/readiness", "/actuator/health/liveness"}) {
            HttpResponse<String> response = get(managementPort, path);
            assertThat(response.statusCode()).as(path).isEqualTo(200);
            assertThat(response.body()).as(path).isEqualTo("{\"status\":\"UP\"}");
        }
    }

    @Test
    @DisplayName("서비스 포트(워커가 요청을 보내는 곳)로는 health 가 나가지 않고, 관리 포트도 health 밖은 닫혀 있다")
    void healthStaysOffTheServicePort() throws Exception {
        assertThat(get(port, "/actuator/health/readiness").statusCode()).isEqualTo(404);
        assertThat(get(port, "/actuator/health").statusCode()).isEqualTo(404);
        assertThat(get(managementPort, "/actuator/env").statusCode()).isEqualTo(404);
        assertThat(get(managementPort, "/actuator/health").statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("readiness 는 앱의 준비 상태만 본다 — DB 는 넣지 않고, 쓰지 않는 Redis(memory)는 전체 헬스에서도 뺀다")
    void readinessLeavesSharedDependenciesOut() {
        assertThat(groups.getPrimary().isMember("db")).as("대조군 — DB 기여자의 이름은 db").isTrue();
        assertThat(groups.get("readiness").isMember("readinessState")).isTrue();
        assertThat(groups.get("readiness").isMember("db")).isFalse();
    }

    @Test
    @DisplayName("memory 모드면 Redis 헬스가 없다 — 붙을 Redis 가 없어 전체 헬스가 늘 DOWN 으로 보이지 않게")
    void redisHealthIsOffInMemoryMode() throws Exception {
        assertThat(get(managementPort, "/actuator/health").body()).doesNotContain("redis");
    }

    private static HttpResponse<String> get(int port, String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
