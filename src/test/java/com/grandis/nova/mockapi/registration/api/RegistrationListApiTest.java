package com.grandis.nova.mockapi.registration.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.grandis.nova.mockapi.global.chaos.FailureMode;
import com.grandis.nova.mockapi.global.chaos.MockConfigStore;
import com.grandis.nova.mockapi.global.config.MockProperties;
import com.grandis.nova.mockapi.registration.RegisterBodies;
import com.grandis.nova.mockapi.registration.domain.Registration;
import com.grandis.nova.mockapi.registration.domain.RegistrationRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 목록 조회 API. 커서 페이지 · 모든 행 · 쿼리 파라미터 검증 · 지연 · 실패 미적용을 HTTP 로 본다.
 *
 * <p>H2 는 시험 클래스끼리 공유되어 다른 시험의 행도 목록에 나온다. 그래서 시험마다 고유한 접두어로 키를 만들고,
 * 커서를 그 접두어로 주어 내 행부터 읽는다 — 접두어보다 크고 내 첫 키보다 작은 키는 그 접두어로 시작해야 하는데,
 * 그런 키는 이 시험만 만든다. 남은 행이 딱 {@code size} 건일 때의 경계는 {@code RegistrationReaderPageTest} 가 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RegistrationListApiTest {

    private static final String PATH = "/external/reservations";

    private static final Instant CANCELED_AT = Instant.parse("2026-10-07T01:00:00.123Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JsonMapper json;

    @Autowired
    private RegistrationRepository repository;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private MockConfigStore store;

    @Autowired
    private MockProperties properties;

    /** 설정을 바꾼 시험은 스스로 되돌린다(팀 규칙). */
    @AfterEach
    void restoreDefaults() {
        store.update(properties.registerLatencyMs(), properties.failureRate(), properties.failureMode());
    }

    /** 이 시험만 쓰는 접두어. 끝의 {@code -} 는 뒤에 붙일 글자보다 작아 커서로 쓰면 내 첫 키 앞에 선다. */
    private static String newPrefix() {
        return "list-" + UUID.randomUUID() + "-";
    }

    private void register(String key) throws Exception {
        mvc.perform(post(PATH)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RegisterBodies.of(key)))
                .andExpect(status().isCreated());
    }

    /** 이름 · 값을 번갈아 받는다. 값을 그대로 넣으려고 URL 문자열이 아니라 param 으로 붙인다(공백 · 한글 · 빈 값). */
    private ResultActions list(String... nameValues) throws Exception {
        MockHttpServletRequestBuilder request = get(PATH);
        for (int i = 0; i < nameValues.length; i += 2) {
            request.param(nameValues[i], nameValues[i + 1]);
        }
        return mvc.perform(request);
    }

    private JsonNode bodyOf(ResultActions result) throws Exception {
        return json.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static List<String> keysOf(JsonNode page) {
        List<String> keys = new ArrayList<>();
        page.get("items").forEach(item -> keys.add(item.get("externalKey").asString()));
        return keys;
    }

    @Test
    @DisplayName("커서 페이지 - 외부 키 순서로 size 건, nextCursor 는 마지막 키. 그 커서로 다음 행부터")
    void pagesByKeyOrder() throws Exception {
        String prefix = newPrefix();
        // 일부러 순서를 섞어 넣는다. 넣은 순서가 아니라 키 순서로 나와야 한다
        for (String suffix : new String[]{"c", "a", "b"}) {
            register(prefix + suffix);
        }

        JsonNode first = bodyOf(list("cursor", prefix, "size", "2"));
        assertThat(keysOf(first)).containsExactly(prefix + "a", prefix + "b");
        assertThat(first.get("nextCursor").asString()).isEqualTo(prefix + "b");

        JsonNode second = bodyOf(list("cursor", first.get("nextCursor").asString(), "size", "2"));
        assertThat(keysOf(second).getFirst()).isEqualTo(prefix + "c");
    }

    /** 정합성 검사는 취소 표식도 봐야 한다("등록 대기인데 Mock 에 취소 표식"). 상품 칸이 비어 있어도 빠지면 안 된다. */
    @Test
    @DisplayName("모든 행 - 등록 · 등록 후 취소 · 취소 표식만 있는 행이 다 나오고, 한 건은 등록 응답과 같은 형식")
    void includesEveryRow() throws Exception {
        String prefix = newPrefix();
        register(prefix + "a");
        register(prefix + "b");
        tx.executeWithoutResult(status -> repository.findById(prefix + "b").orElseThrow().cancel(CANCELED_AT));
        repository.saveAndFlush(Registration.cancelMarker(prefix + "c", CANCELED_AT));

        list("cursor", prefix, "size", "3")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].externalKey").value(prefix + "a"))
                .andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.items[0].customerId").value(Integer.parseInt(RegisterBodies.CUSTOMER_REF)))
                .andExpect(jsonPath("$.items[0].productId").value(Integer.parseInt(RegisterBodies.ITEM_CODE)))
                .andExpect(jsonPath("$.items[0].sku").value(RegisterBodies.OPTION_CODE))
                .andExpect(jsonPath("$.items[1].externalKey").value(prefix + "b"))
                .andExpect(jsonPath("$.items[1].status").value("CANCELED"))
                .andExpect(jsonPath("$.items[1].canceledAt").value("2026-10-07T01:00:00.123Z"))
                .andExpect(jsonPath("$.items[2].externalKey").value(prefix + "c"))
                .andExpect(jsonPath("$.items[2].status").value("CANCELED"))
                // 값이 없어도 필드를 생략하지 않는다
                .andExpect(jsonPath("$.items[2].externalNumber").value(nullValue()))
                .andExpect(jsonPath("$.items[2].customerId").value(nullValue()))
                .andExpect(jsonPath("$.items[2].sku").value(nullValue()));

        JsonNode item = bodyOf(list("cursor", prefix, "size", "1")).get("items").get(0);
        JsonNode byNumber = bodyOf(mvc.perform(get(PATH + "/{externalNumber}", item.get("externalNumber").asString())));
        assertThat(item).isEqualTo(byNumber);
    }

    /** 키는 영문 · 숫자 · {@code . _ -} 100자까지라 {@code z} 100개보다 큰 키는 없다. */
    @Test
    @DisplayName("끝 - 더 없으면 items 는 비고 nextCursor 는 null (생략하지 않는다)")
    void lastPage() throws Exception {
        list("cursor", "z".repeat(100))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
    }

    @Test
    @DisplayName("파라미터 없이 - 처음부터 기본 500건까지. 200")
    void defaults() throws Exception {
        register(newPrefix() + "a");

        JsonNode page = bodyOf(list());
        assertThat(page.get("items").size()).isBetween(1, 500);
    }

    /** 정합성 검사가 Mock 결과를 확인하려고 부르는 길이라, 실패율을 올린 상태에서도 막히면 안 된다. */
    @Test
    @DisplayName("지연 · 실패 설정을 적용하지 않는다 - 실패율 100% 에서도 200")
    void noFailureInjection() throws Exception {
        store.update(0, 1.0, FailureMode.HTTP_5XX);

        list("size", "1").andExpect(status().isOk());
    }

    /** 모르는 이름을 조용히 버리면 productId=12 로 거른 줄 알고 전체를 받는다. */
    @Test
    @DisplayName("모르는 쿼리 파라미터 - 400")
    void unknownParam() throws Exception {
        list("productId", "12")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errorMessage")
                        .value("productId 은(는) 알 수 없는 쿼리 파라미터입니다. 받을 수 있는 것: cursor, size"));
    }

    @Test
    @DisplayName("같은 파라미터 두 번 - 400. 어느 값을 쓸지 고르지 않는다")
    void duplicateParam() throws Exception {
        mvc.perform(get(PATH).param("size", "1", "2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage").value("size 이(가) 두 번 왔습니다."));
    }

    @Test
    @DisplayName("size 가 1~1000 정수가 아니면 400 - 0 · 1001 · 글자 · 소수 · 빈 값")
    void invalidSize() throws Exception {
        for (String size : new String[]{"0", "1001", "abc", "1.5", "", "-1", "99999"}) {
            list("size", size)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.errorMessage").value("size 은(는) 1~1000 사이 정수여야 합니다. 받은 값: " + size));
        }
        list("size", "1000").andExpect(status().isOk());
    }

    @Test
    @DisplayName("cursor 가 키 형식이 아니면 400 - 빈 값 · 공백 · 101자")
    void invalidCursor() throws Exception {
        for (String cursor : new String[]{"", "a b", "k".repeat(101), "키"}) {
            list("cursor", cursor)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorMessage")
                            .value("cursor 은(는) 영문 · 숫자 · . _ - 로 된 1~100자여야 합니다."));
        }
    }
}
