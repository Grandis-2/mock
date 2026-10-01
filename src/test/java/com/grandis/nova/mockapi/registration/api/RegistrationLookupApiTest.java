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
import com.grandis.nova.mockapi.registration.domain.Registration;
import com.grandis.nova.mockapi.registration.domain.RegistrationRepository;
import java.time.Instant;
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
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 번호 조회 · 키 조회 API. 명세 v5 의 응답 형식과 세 가지 행 상태별 응답을 HTTP 로 본다.
 *
 * <p>키 조회가 커밋 전 등록을 기다리는지는 H2 로 볼 수 없다. {@code RegistrationConcurrencyTest} 가
 * 진짜 MySQL 로 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RegistrationLookupApiTest {

    private static final String PATH = "/external/reservations";
    private static final String BODY = """
            {"customerId":1001,"productId":12,"sku":"SM-G999-256-BLK"}""";

    /** 밀리초가 0 이 아니어야 응답의 시각 형식을 글자 그대로 비교할 수 있다. */
    private static final Instant CANCELED_AT = Instant.parse("2026-09-16T10:10:00.123Z");

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

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private JsonNode register(String key) throws Exception {
        String body = mvc.perform(post(PATH)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    /** 응답의 시각 형식을 글자 그대로 비교하려고 시각을 정해 원장을 직접 취소한다. 취소 API 는 {@link CancellationApiTest}. */
    private void cancel(String key) {
        tx.executeWithoutResult(status -> repository.findById(key).orElseThrow().cancel(CANCELED_AT));
    }

    private ResultActions byNumber(String number) throws Exception {
        return mvc.perform(get(PATH + "/{externalNumber}", number));
    }

    private ResultActions byKey(String key) throws Exception {
        return mvc.perform(get(PATH + "/by-key/{externalKey}", key));
    }

    private JsonNode bodyOf(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    @Test
    @DisplayName("번호 조회 - 200, 등록 응답과 한 글자도 다르지 않다")
    void byNumberSameAsRegistration() throws Exception {
        JsonNode registered = register(newKey());

        JsonNode found = bodyOf(byNumber(registered.get("externalNumber").asString())
                .andExpect(status().isOk()));

        assertThat(found).isEqualTo(registered);
    }

    @Test
    @DisplayName("취소된 등록의 번호 조회 - 200, 숨기지 않고 CANCELED 와 취소 시각을 준다")
    void byNumberShowsCanceled() throws Exception {
        String key = newKey();
        String number = register(key).get("externalNumber").asString();
        cancel(key);

        byNumber(number)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalNumber").value(number))
                .andExpect(jsonPath("$.status").value("CANCELED"))
                .andExpect(jsonPath("$.canceledAt").value("2026-09-16T10:10:00.123Z"));
    }

    /** 형식이 틀린 번호를 조회까지 보내면 404 NOT_FOUND("그 기록이 없다")로 나가 뜻이 섞인다. */
    @Test
    @DisplayName("번호가 영문 · 숫자 · . _ - 1~100자가 아니면 400 - 조회하지 않는다")
    void byNumberInvalidFormat() throws Exception {
        for (String number : new String[]{"R 1", "번호-1", "R".repeat(101), ".."}) {
            byNumber(number)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.errorMessage")
                            .value("externalNumber 은(는) 영문 · 숫자 · . _ - 로 된 1~100자여야 합니다."));
        }
    }

    @Test
    @DisplayName("없는 번호 - 404 NOT_FOUND")
    void byNumberNotFound() throws Exception {
        byNumber("R-19990101-0000000000")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"))
                .andExpect(jsonPath("$.errorMessage").value("등록되지 않았습니다."))
                .andExpect(jsonPath("$.replayable").value(false))
                .andExpect(jsonPath("$.externalNumber").value(nullValue()));
    }

    @Test
    @DisplayName("키 조회 · 등록됨 - 등록 1개(ACTIVE), 취소 시각 없음, storedOutcome SUCCESS")
    void byKeyActive() throws Exception {
        String key = newKey();
        JsonNode registered = register(key);

        JsonNode found = bodyOf(byKey(key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalKey").value(key))
                // 값이 없어도 필드를 생략하지 않는다
                .andExpect(jsonPath("$.cancelMarkerAt").value(nullValue()))
                .andExpect(jsonPath("$.registrations.length()").value(1))
                .andExpect(jsonPath("$.registrations[0].externalNumber").value(registered.get("externalNumber").asString()))
                .andExpect(jsonPath("$.registrations[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.registrations[0].confirmedAt").value(registered.get("confirmedAt").asString()))
                .andExpect(jsonPath("$.registrations[0].canceledAt").value(nullValue()))
                .andExpect(jsonPath("$.storedOutcome").value("SUCCESS")));

        // 명세의 by-key 응답은 등록 응답의 전 필드가 아니라 이 넷만 담는다
        assertThat(found.get("registrations").get(0).propertyNames())
                .containsExactly("externalNumber", "status", "confirmedAt", "canceledAt");
    }

    /**
     * 워커는 cancelMarkerAt 하나로 "취소된 키" 를 안다. 등록 후 취소에서 비어 있으면 워커가 그 번호로
     * 확정해 버린다. storedOutcome 이 null 인 이유는 다시 등록하면 201 재생이 아니라 409 이기 때문이다.
     */
    @Test
    @DisplayName("키 조회 · 등록 후 취소 - 등록 1개(CANCELED), cancelMarkerAt 도 채우고 storedOutcome 은 null")
    void byKeyCanceledAfterRegistration() throws Exception {
        String key = newKey();
        String number = register(key).get("externalNumber").asString();
        cancel(key);

        byKey(key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelMarkerAt").value("2026-09-16T10:10:00.123Z"))
                .andExpect(jsonPath("$.registrations.length()").value(1))
                .andExpect(jsonPath("$.registrations[0].externalNumber").value(number))
                .andExpect(jsonPath("$.registrations[0].status").value("CANCELED"))
                .andExpect(jsonPath("$.registrations[0].canceledAt").value("2026-09-16T10:10:00.123Z"))
                .andExpect(jsonPath("$.storedOutcome").value(nullValue()));
    }

    @Test
    @DisplayName("키 조회 · 취소 표식만 - 404 가 아니라 200, 등록 0개, cancelMarkerAt 있음")
    void byKeyCancelMarkerOnly() throws Exception {
        String key = newKey();
        repository.saveAndFlush(Registration.cancelMarker(key, CANCELED_AT));

        byKey(key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalKey").value(key))
                .andExpect(jsonPath("$.cancelMarkerAt").value("2026-09-16T10:10:00.123Z"))
                .andExpect(jsonPath("$.registrations.length()").value(0))
                .andExpect(jsonPath("$.storedOutcome").value(nullValue()));
    }

    @Test
    @DisplayName("키 조회 · 기록 없음 - 404 NOT_FOUND")
    void byKeyNotFound() throws Exception {
        byKey(newKey())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"))
                .andExpect(jsonPath("$.errorMessage").value("키에 대한 기록이 없습니다."))
                .andExpect(jsonPath("$.replayable").value(false));
    }

    @Test
    @DisplayName("키가 영문 · 숫자 · . _ - 1~100자가 아니면 400 - 100자는 받는다")
    void byKeyInvalidFormat() throws Exception {
        for (String key : new String[]{"   ", "k".repeat(101), "a b", "키-1"}) {
            byKey(key)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.errorMessage")
                            .value("externalKey 은(는) 영문 · 숫자 · . _ - 로 된 1~100자여야 합니다."));
        }
        // 인메모리 DB 는 시험 클래스끼리 공유된다. 다른 시험이 등록한 키와 겹치지 않게 새로 만든다
        String longest = (newKey() + "k".repeat(100)).substring(0, 100);
        byKey(longest).andExpect(status().isNotFound());
    }

    /** 워커가 결과를 확인하는 길이다. 실패율을 올려도 막히면 안 된다(명세: 조회에는 지연·실패 미적용). */
    @Test
    @DisplayName("실패율 100% 에서도 조회는 성공한다")
    void lookupsIgnoreFailureInjection() throws Exception {
        String key = newKey();
        String number = register(key).get("externalNumber").asString();
        store.update(0, 1.0, FailureMode.HTTP_5XX);

        byNumber(number).andExpect(status().isOk());
        byKey(key).andExpect(status().isOk());
    }
}
