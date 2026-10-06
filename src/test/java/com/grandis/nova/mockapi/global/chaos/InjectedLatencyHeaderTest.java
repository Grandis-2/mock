package com.grandis.nova.mockapi.global.chaos;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.grandis.nova.mockapi.global.config.MockProperties;
import com.grandis.nova.mockapi.registration.RegisterBodies;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 등록 응답의 {@code X-Mock-Injected-Latency-Ms} — 이 요청에 실제로 뽑힌 지연.
 *
 * <p>부하 판정이 요청마다 {@code 관측 − 이 값} 으로 오버헤드를 구한다. 헤더가 빠진 응답이 있으면 그 요청의
 * 오버헤드를 알 수 없으므로, 주입 실패의 500 과 지연 0 에도 실려야 한다. 시험 설정은 지터 0 이라 뽑히는
 * 값이 설정값과 같다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class InjectedLatencyHeaderTest {

    private static final String HEADER = DefaultFailureInjector.INJECTED_LATENCY_HEADER;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MockConfigStore config;

    @Autowired
    private MockProperties properties;

    @AfterEach
    void restoreDefaults() {
        config.update(properties.registerLatencyMs(), properties.failureRate(), properties.failureMode());
    }

    @Test
    @DisplayName("새 등록 201 에 실제로 기다린 시간이 실린다")
    void acceptedCarriesInjectedLatency() throws Exception {
        config.update(120, 0.0, FailureMode.HTTP_5XX);

        register("hdr-" + UUID.randomUUID())
                .andExpect(status().isCreated())
                .andExpect(header().string(HEADER, "120"));
    }

    @Test
    @DisplayName("지연이 0 이어도 0 을 싣는다 — 헤더가 없는 것과 구분한다")
    void zeroLatencyIsStillAnnounced() throws Exception {
        config.update(0, 0.0, FailureMode.HTTP_5XX);

        register("hdr-" + UUID.randomUUID())
                .andExpect(status().isCreated())
                .andExpect(header().string(HEADER, "0"));
    }

    /** 실패한 요청도 지연은 이미 겪은 뒤다. 일시 실패의 응답 시간도 오버헤드를 따진다. */
    @Test
    @DisplayName("주입 실패의 500 에도 실린다")
    void injectedFailureCarriesInjectedLatency() throws Exception {
        config.update(80, 1.0, FailureMode.HTTP_5XX);

        register("hdr-" + UUID.randomUUID())
                .andExpect(status().isInternalServerError())
                .andExpect(header().string(HEADER, "80"));
    }

    /** 재생도 지연 · 주사위를 거친 뒤에 원장을 읽는다. */
    @Test
    @DisplayName("재생 응답에도 실린다")
    void replayCarriesInjectedLatency() throws Exception {
        String key = "hdr-replay-" + UUID.randomUUID();
        config.update(0, 0.0, FailureMode.HTTP_5XX);
        register(key).andExpect(status().isCreated());

        config.update(60, 0.0, FailureMode.HTTP_5XX);
        register(key)
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Idempotent-Replay", "true"))
                .andExpect(header().string(HEADER, "60"));
    }

    private ResultActions register(String key) throws Exception {
        return mvc.perform(post("/external/reservations")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(RegisterBodies.of(key)));
    }
}
