package com.grandis.nova.mockapi.control.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.grandis.nova.mockapi.global.chaos.FailureMode;
import com.grandis.nova.mockapi.global.chaos.MockConfigStore;
import com.grandis.nova.mockapi.global.config.MockProperties;
import com.grandis.nova.mockapi.registration.domain.RegistrationRepository;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * <b>처리 도중</b> 설정을 바꿔도 진행 중인 등록은 시작할 때의 설정으로 끝나는지 본다(외부 리뷰 M5).
 *
 * <p>다른 시험은 요청 <b>전에</b> 설정을 바꿔, 요청 안에서 설정을 다시 읽어도 통과한다. 다시 읽으면 시연 중
 * 실패율을 올리는 순간 이미 대기 중이던 요청들까지 실패로 바뀌고, 응답의 {@code X-Mock-Config-Version} 이
 * 실제로 적용된 설정과 어긋나 "어떤 설정에서 난 결과인가" 를 가를 수 없다.
 *
 * <p>순서는 시간이 아니라 래치 두 개로 고정한다. 설정 보관소에 스파이를 걸어, 진행 중인 요청이 <b>스냅숏을 뜬
 * 직후 멈추게</b> 하고(그 뒤 지연 · 주사위 · 커밋은 아직이다) 그사이 설정 API 로 실패율 1.0 을 건 다음 풀어 준다.
 * 다시 읽는 구현이면 풀린 뒤 새 설정을 읽어 500 이 된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConfigChangeInFlightTest {

    private static final String BODY = """
            {"customerId":1001,"productId":12,"sku":"SM-G999-256-BLK"}""";

    /** 바꾸기 전 설정의 지연. 바꾼 뒤(0)와 달라야 주입 지연 헤더로 어느 설정을 썼는지 보인다. */
    private static final int IN_FLIGHT_LATENCY_MS = 300;

    @Autowired
    private MockMvc mvc;

    @MockitoSpyBean
    private MockConfigStore store;

    @Autowired
    private MockProperties properties;

    @Autowired
    private RegistrationRepository repository;

    private final CountDownLatch snapshotTaken = new CountDownLatch(1);
    private final CountDownLatch allowInFlight = new CountDownLatch(1);
    private final AtomicBoolean first = new AtomicBoolean(true);

    /** 첫 등록만 스냅숏 직후에 멈춘다. 바꾼 뒤의 등록까지 멈추면 시험이 서로를 기다린다. */
    @BeforeEach
    void setUp() {
        repository.deleteAll();
        doAnswer(invocation -> {
            Object snapshot = invocation.callRealMethod();
            if (first.compareAndSet(true, false)) {
                snapshotTaken.countDown();
                allowInFlight.await(10, TimeUnit.SECONDS);
            }
            return snapshot;
        }).when(store).snapshot();
    }

    /** 설정·결함을 바꾼 시험은 스스로 되돌린다(팀 규칙). */
    @AfterEach
    void restoreDefaults() {
        store.update(properties.registerLatencyMs(), properties.failureRate(), properties.failureMode());
    }

    private MockHttpServletResponse register(String key) throws Exception {
        return mvc.perform(post("/external/reservations")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andReturn().getResponse();
    }

    @Test
    @DisplayName("처리 도중 실패율 1.0 으로 바꿔도 진행 중인 등록은 시작 때 설정으로 201, 이후 요청은 새 설정으로 500")
    void inFlightRequestKeepsItsSnapshot() throws Exception {
        int before = store.update(IN_FLIGHT_LATENCY_MS, 0.0, FailureMode.HTTP_5XX).snapshot().configVersion();

        CompletableFuture<MockHttpServletResponse> inFlight = CompletableFuture.supplyAsync(() -> {
            try {
                return register("inflight-1");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(snapshotTaken.await(5, TimeUnit.SECONDS)).as("진행 중인 요청이 스냅숏을 떴다").isTrue();

        try {
            mvc.perform(put("/external/config")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"registerLatencyMs":0,"failureRate":1.0,"failureMode":"HTTP_5XX"}"""))
                    .andExpect(status().isOk());
        } finally {
            // 바꾸기가 실패해도 멈춘 요청은 풀어 준다. 안 풀면 아래 get 이 시간 초과로 원인을 가린다
            allowInFlight.countDown();
        }

        MockHttpServletResponse after = register("after-change-1");
        assertThat(after.getStatus()).as("바꾼 뒤의 요청은 새 설정(실패율 1.0)").isEqualTo(500);

        MockHttpServletResponse started = inFlight.get(10, TimeUnit.SECONDS);
        assertThat(started.getStatus()).as("진행 중이던 요청은 시작 때 설정(실패율 0)").isEqualTo(201);
        assertThat(started.getHeader("X-Mock-Config-Version")).isEqualTo(String.valueOf(before));
        assertThat(started.getHeader("X-Mock-Injected-Latency-Ms")).isEqualTo(String.valueOf(IN_FLIGHT_LATENCY_MS));
        assertThat(repository.findById("inflight-1")).isPresent();
        assertThat(repository.findById("after-change-1")).isEmpty();
    }
}
