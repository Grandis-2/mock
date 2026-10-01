package com.grandis.nova.mockapi.control.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.grandis.nova.mockapi.control.application.ResetBarrier;
import com.grandis.nova.mockapi.global.chaos.FailureMode;
import com.grandis.nova.mockapi.global.chaos.MockConfigStore;
import com.grandis.nova.mockapi.global.config.MockProperties;
import com.grandis.nova.mockapi.registration.domain.RegistrationRepository;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 초기화가 진행 중인 등록 · 취소를 막는지. 리뷰 M7 의 재현 순서를 그대로 옮겼다.
 *
 * <p>막지 않으면 — 지연 중인 등록 K → K 취소 200 → 초기화 → 키 조회 404 → 지연이 끝난 등록이 201 로
 * 커밋되어 <b>취소 성공을 받은 K 가 ACTIVE 로 살아난다.</b> 초기화가 취소 표식까지 지웠기 때문이다.
 *
 * <p>지연 중인 요청을 만들려고 실제 지연을 쓴다. 시험 설정은 지터 0 이라 정확히 그만큼 걸린다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ResetBarrierApiTest {

    private static final String BODY = """
            {"customerId":1001,"productId":12,"sku":"SM-G999-256-BLK"}""";
    private static final String RESET = """
            {"confirm":"RESET"}""";

    /** 등록이 지연에 머무는 동안 취소 · 초기화를 끼워 넣을 만큼. 너무 길면 시험이 느려진다. */
    private static final int LATENCY_MS = 1500;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ResetBarrier barrier;

    @Autowired
    private MockConfigStore config;

    @Autowired
    private MockProperties properties;

    @Autowired
    private RegistrationRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAllInBatch();
    }

    @AfterEach
    void restoreDefaults() {
        config.update(properties.registerLatencyMs(), properties.failureRate(), properties.failureMode());
    }

    @Test
    @DisplayName("지연 중 등록 → 취소 → 초기화는 409 → 등록은 KEY_CANCELED → 그 뒤 초기화 → 취소한 키가 살아나지 않는다")
    void resetRefusedWhileRegistrationInFlight() throws Exception {
        String key = "barrier-" + UUID.randomUUID();
        config.update(LATENCY_MS, 0.0, FailureMode.HTTP_5XX);

        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<MvcResult> registration = pool.submit(() -> mvc.perform(post("/external/reservations")
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(BODY)).andReturn());
            awaitInFlight(1);

            // 지연 중인 키를 취소한다. 등록 전이라 표식만 남는다.
            mvc.perform(post("/external/cancellations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"externalKey":"%s","reason":"USER_CANCEL"}""".formatted(key)))
                    .andExpect(status().isOk());

            // 등록이 아직 지연 중이다. 그대로 지우면 표식이 사라져 등록이 ACTIVE 로 살아난다.
            mvc.perform(post("/external/reset").contentType(MediaType.APPLICATION_JSON).content(RESET))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("RESET_BUSY"))
                    .andExpect(jsonPath("$.errorMessage").value(org.hamcrest.Matchers.containsString("1건")));

            // 표식이 남아 있으니 늦게 진행된 등록은 409 로 끝난다.
            MvcResult registered = registration.get(10, TimeUnit.SECONDS);
            assertThat(registered.getResponse().getStatus()).isEqualTo(409);
            assertThat(registered.getResponse().getContentAsString()).contains("KEY_CANCELED");
        }

        assertThat(barrier.inFlight()).isZero();
        mvc.perform(post("/external/reset").contentType(MediaType.APPLICATION_JSON).content(RESET))
                .andExpect(status().isOk());

        // 리뷰 재현에서는 여기서 ACTIVE 였다.
        mvc.perform(get("/external/reservations/by-key/" + key)).andExpect(status().isNotFound());
        assertThat(repository.count()).isZero();
    }

    @Test
    @DisplayName("진행 중인 등록이 없으면 초기화는 바로 된다 — 조회는 진행 중으로 세지 않는다")
    void resetAllowedWhenIdle() throws Exception {
        mvc.perform(get("/external/reservations/by-key/idle-" + UUID.randomUUID()))
                .andExpect(status().isNotFound());

        assertThat(barrier.inFlight()).isZero();
        mvc.perform(post("/external/reset").contentType(MediaType.APPLICATION_JSON).content(RESET))
                .andExpect(status().isOk());
    }

    /** 고정 sleep 대신 조건을 기다린다. 넉넉한 상한을 두고 넘으면 실패한다. */
    private void awaitInFlight(int expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (barrier.inFlight() < expected) {
            assertThat(System.nanoTime()).as("진행 중 요청이 %d건이 되지 않았다", expected).isLessThan(deadline);
            LockSupport.parkNanos(Duration.ofMillis(5).toNanos());
        }
    }
}
