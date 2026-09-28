package com.grandis.nova.mockapi.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.grandis.nova.mockapi.global.chaos.InMemoryFaultStore;
import com.grandis.nova.mockapi.global.config.StrictJsonConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 결함 주입 API.
 *
 * <p>이 API 는 워커가 부르지 않는 시험 도구다. 잘못된 입력이 500 이 되면 워커가 "일시 실패" 로 읽고
 * 재시도하므로, 여기서 보는 것은 <b>전부 400 인지</b>다.
 *
 * <p>{@code controllers=} 로 범위를 좁힌다. 안 좁히면 새 컨트롤러가 생길 때마다 이 시험이 깨진다.
 * 슬라이스는 {@link StrictJsonConfig} 와 보관소를 스캔하지 않아 {@code @Import} 로 넣는다 — 보관소는
 * 목이 아니라 실물이라 정말 걸렸는지까지 볼 수 있다.
 */
@WebMvcTest(controllers = FaultController.class)
@Import({StrictJsonConfig.class, InMemoryFaultStore.class})
class FaultApiTest {

    private static final String PATH = "/external/faults";
    private static final String KEY = "test-lost-1";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private InMemoryFaultStore store;

    /**
     * 보관소는 싱글턴이라 시험 메서드끼리 공유된다. 비우지 않으면 앞 시험이 걸어두고 소비하지 않은
     * 결함이 남아, "걸리지 않았다" 단정이 메서드 실행 순서에 따라 갈린다.
     */
    @BeforeEach
    void emptyStore() {
        store.clear();
    }

    private ResultActions inject(String body) throws Exception {
        return mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    @DisplayName("결함을 걸면 201 이고 무엇을 걸었는지 돌려준다")
    void injects() throws Exception {
        inject("""
                {"externalKey":"test-lost-1","faultType":"RESPONSE_LOST_AFTER_COMMIT"}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.externalKey").value(KEY))
                .andExpect(jsonPath("$.faultType").value("RESPONSE_LOST_AFTER_COMMIT"))
                .andExpect(jsonPath("$.createdAt").exists());

        // 응답만 보면 정말 보관됐는지 알 수 없다
        assertThat(store.consumeResponseLost(KEY)).isTrue();
    }

    @Test
    @DisplayName("잘못된 faultType 은 400 이고 허용값을 알려준다")
    void unknownFaultType() throws Exception {
        inject("""
                {"externalKey":"test-lost-1","faultType":"BOOM"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errorMessage")
                        .value("faultType 은(는) RESPONSE_LOST_AFTER_COMMIT 중 하나여야 합니다. 받은 값: BOOM"));

        assertThat(store.consumeResponseLost(KEY)).isFalse();
    }

    @Test
    @DisplayName("필수 값이 없으면 400 이고 어느 필드인지 알려준다")
    void missingFields() throws Exception {
        inject("""
                {"faultType":"RESPONSE_LOST_AFTER_COMMIT"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage").value("externalKey 은(는) 필수입니다."));

        inject("""
                {"externalKey":"test-lost-1"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage").value("faultType 은(는) 필수입니다."));
    }

    /** 키 칸이 varchar(100) 이라 넘는 키는 등록될 수 없다. 걸어두면 영원히 발동하지 않는다. */
    @Test
    @DisplayName("키가 100자를 넘으면 400, 100자는 통과")
    void keyLength() throws Exception {
        inject("""
                {"externalKey":"%s","faultType":"RESPONSE_LOST_AFTER_COMMIT"}""".formatted("k".repeat(101)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage").value("externalKey 은(는) 100자 이하여야 합니다."));

        inject("""
                {"externalKey":"%s","faultType":"RESPONSE_LOST_AFTER_COMMIT"}""".formatted("k".repeat(100)))
                .andExpect(status().isCreated());
    }

    /**
     * 모르는 필드를 조용히 버리면 걸리지도 않은 결함을 걸린 줄 알게 된다.
     * 계약 전체의 규칙이지만 이 API 에서도 성립하는지 본다.
     */
    @Test
    @DisplayName("계약에 없는 필드가 오면 400")
    void unknownField() throws Exception {
        inject("""
                {"externalKey":"test-lost-1","faultType":"RESPONSE_LOST_AFTER_COMMIT","delayMs":100}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage", containsString("delayMs 은(는) 알 수 없는 필드입니다.")))
                .andExpect(jsonPath("$.errorMessage", containsString("externalKey")))
                .andExpect(jsonPath("$.errorMessage", containsString("faultType")));

        assertThat(store.consumeResponseLost(KEY)).isFalse();
    }
}
