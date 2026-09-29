package com.grandis.nova.mockapi.control.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.grandis.nova.mockapi.global.chaos.InMemoryFaultStore;
import com.grandis.nova.mockapi.registration.domain.Registration;
import com.grandis.nova.mockapi.registration.domain.RegistrationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 결함을 HTTP 로 걸고 HTTP 로 등록해 <b>목 없이</b> 전 경로를 본다. NV-18 의 통과 조건이다.
 *
 * <p>{@code RegistrationApiTest} 는 {@code FaultHook} 을 목으로 바꿔 등록 처리 7단계만 보고,
 * {@code ConnectionDropperE2eTest} 는 실제 소켓만 본다. 둘 다 통과해도 <b>결함 주입 API 로 걸어둔
 * 결함이 등록에 닿는지</b>는 아무도 증명하지 않는다. 그 사이를 여기서 잇는다.
 *
 * <p>응답 유실은 {@code timeout-hold-ms} 만큼 붙잡으므로 0 으로 두고 본다. 붙잡힌 응답이 여기서
 * 500 · 빈 본문으로 보이는 것은 MockMvc 에 실제 소켓이 없어서다 — 워커가 정말 읽기 타임아웃
 * (UNKNOWN)을 겪는지는 {@code ConnectionDropperE2eTest} 가 본다. <b>이 500 을 고치려 들지 말 것.</b>
 */
@SpringBootTest(properties = "mock.timeout-hold-ms=0")
@AutoConfigureMockMvc
class FaultFlowTest {

    private static final String FAULTS = "/external/faults";
    private static final String RESERVATIONS = "/external/reservations";
    private static final String KEY = "flow-lost-1";
    private static final String BODY = """
            {"customerId":1001,"productId":12,"sku":"SM-G999-256-BLK"}""";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private RegistrationRepository repository;

    @Autowired
    private InMemoryFaultStore faults;

    @BeforeEach
    void emptyEverything() {
        repository.deleteAll();
        faults.clear();
    }

    private ResultActions register(String key) throws Exception {
        return mvc.perform(post(RESERVATIONS)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY));
    }

    /**
     * 이 시나리오가 프로젝트의 핵심이다 — 외부는 저장에 성공했는데 응답만 잃은 상태에서, 같은 키로
     * 다시 보내면 <b>새 등록이 아니라 저장된 성공이 재생</b>되어야 한다. 새 번호가 발급되면 같은
     * 예약이 두 번 등록된 것이다.
     */
    @Test
    @DisplayName("결함 주입 → 등록은 커밋되고 응답은 유실된다 → 재시도는 같은 번호를 재생한다")
    void injectedFaultLosesResponseThenReplays() throws Exception {
        mvc.perform(post(FAULTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalKey":"flow-lost-1","faultType":"RESPONSE_LOST_AFTER_COMMIT"}"""))
                .andExpect(status().isCreated());

        register(KEY)
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(""));

        // 응답은 없었지만 등록은 남아 있다. 이것이 워커가 결과를 모르는 상태의 실체다
        Registration committed = repository.findById(KEY).orElseThrow();
        assertThat(committed.isActive()).isTrue();

        register(KEY)
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Idempotent-Replay", "true"))
                .andExpect(content().string(containsString(committed.externalNumber())));

        assertThat(repository.count()).isEqualTo(1);
    }

    /**
     * 한 번 발동하면 자동 해제된다. 해제되지 않으면 재시도마저 응답을 잃어 예약이 끝내 확정에
     * 도달하지 못한다.
     */
    @Test
    @DisplayName("결함은 한 번만 발동한다 — 재시도는 붙잡히지 않는다")
    void faultFiresOnlyOnce() throws Exception {
        mvc.perform(post(FAULTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalKey":"flow-lost-1","faultType":"RESPONSE_LOST_AFTER_COMMIT"}"""))
                .andExpect(status().isCreated());

        register(KEY).andExpect(status().isInternalServerError());

        assertThat(faults.consumeResponseLost(KEY)).isFalse();
    }

    /** 결함을 걸지 않은 키는 평소처럼 201 이다. 키끼리 섞이면 시험 전체를 믿을 수 없다. */
    @Test
    @DisplayName("결함을 걸지 않은 키는 영향받지 않는다")
    void otherKeyUnaffected() throws Exception {
        mvc.perform(post(FAULTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalKey":"flow-lost-1","faultType":"RESPONSE_LOST_AFTER_COMMIT"}"""))
                .andExpect(status().isCreated());

        register("flow-clean-1")
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Idempotent-Replay", "false"));
    }
}
