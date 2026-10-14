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
import com.grandis.nova.mockapi.registration.RegisterBodies;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

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
                    .content(RegisterBodies.of(key))).andReturn());
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

    /**
     * 스프링은 {@code ;} 뒤 경로 변수를 떼고 매핑하므로 이 요청도 등록 컨트롤러까지 간다. 필터가
     * {@code getRequestURI()} 로 경로를 다시 비교하던 때는 {@code ;x=1} 이 남아 진행 중으로 세지 않았고,
     * 초기화가 200 · 등록이 201 ACTIVE 로 장벽을 지나갔다(리뷰 실측, 지연 3초).
     */
    @Test
    @DisplayName("경로에 ; 가 붙은 등록도 진행 중으로 센다 — 장벽을 돌아가지 못한다")
    void pathParametersDoNotBypass() throws Exception {
        String key = "barrier-semicolon-" + UUID.randomUUID();
        config.update(LATENCY_MS, 0.0, FailureMode.HTTP_5XX);

        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<MvcResult> registration = pool.submit(() -> mvc.perform(post("/external/reservations;x=1")
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(RegisterBodies.of(key))).andReturn());
            awaitInFlight(1);

            mvc.perform(post("/external/reset").contentType(MediaType.APPLICATION_JSON).content(RESET))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("RESET_BUSY"));

            assertThat(registration.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(201);
        }
    }

    /** 지연 중인 응모가 초기화 뒤에 커밋되면 다음 시험의 회차에 앞 시험의 응모가 섞인다. 추첨도 쓰는 요청이라 같이 센다. */
    @Test
    @DisplayName("지연 중인 럭키드로우 응모 · 추첨도 진행 중으로 센다 — 초기화는 409, 둘은 그대로 끝난다")
    void resetRefusedWhileDrawRequestsInFlight() throws Exception {
        String drawId = "barrier-draw-" + UUID.randomUUID();
        config.update(0, 0.0, FailureMode.HTTP_5XX);
        for (int i = 0; i < 2; i++) {
            mvc.perform(drawEntry(UUID.randomUUID().toString(), drawId)).andExpect(status().isCreated());
        }
        config.update(LATENCY_MS, 0.0, FailureMode.HTTP_5XX);

        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<MvcResult> entry = pool.submit(() ->
                    mvc.perform(drawEntry(UUID.randomUUID().toString(), "barrier-other-" + UUID.randomUUID()))
                            .andReturn());
            Future<MvcResult> draw = pool.submit(() -> mvc.perform(post("/external/draws/" + drawId + "/draw")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"winnerCount":1}""")).andReturn());
            awaitInFlight(2);

            mvc.perform(post("/external/reset").contentType(MediaType.APPLICATION_JSON).content(RESET))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("RESET_BUSY"))
                    .andExpect(jsonPath("$.errorMessage").value(org.hamcrest.Matchers.containsString("2건")));

            assertThat(entry.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(201);
            assertThat(draw.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
        }
    }

    private static MockHttpServletRequestBuilder drawEntry(
            String entryId, String drawId) {
        return post("/external/draw-entries")
                .header("Idempotency-Key", entryId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"entryId":"%s","drawId":"%s","customerId":"%s"}""".formatted(entryId, drawId, UUID.randomUUID()));
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
