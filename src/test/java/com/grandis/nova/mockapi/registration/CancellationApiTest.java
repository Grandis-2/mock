package com.grandis.nova.mockapi.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.grandis.nova.mockapi.global.chaos.FailureMode;
import com.grandis.nova.mockapi.global.chaos.MockConfigStore;
import com.grandis.nova.mockapi.global.config.MockProperties;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 예약 취소 API. 명세 v5 의 확인 시나리오와 요청 검증을 HTTP 로 본다.
 *
 * <p>H2 라 순서대로 부를 때의 판정만 본다. 등록과 취소가 동시에 들어오는 경합은
 * {@link RegistrationConcurrencyTest} 가 진짜 MySQL 로 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CancellationApiTest {

    private static final String PATH = "/external/cancellations";
    private static final String BODY = """
            {"customerId":1001,"productId":12,"sku":"SM-G999-256-BLK"}""";

    /** 응답 시각은 UTC Z 이고 밀리초까지다. */
    private static final String UTC_MILLIS = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JsonMapper json;

    @Autowired
    private RegistrationRepository repository;

    @Autowired
    private MockConfigStore store;

    @Autowired
    private MockProperties properties;

    /** 설정을 바꾼 시험은 스스로 되돌린다(팀 규칙). */
    @AfterEach
    void restoreDefaults() {
        store.update(properties.registerLatencyMs(), properties.failureRate(), properties.failureMode());
    }

    /** 인메모리 DB 는 시험 클래스끼리 공유된다. 다른 시험의 행과 겹치지 않게 매번 새로 만든다. */
    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private ResultActions register(String key) throws Exception {
        return mvc.perform(post("/external/reservations")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY));
    }

    private String registeredNumber(String key) throws Exception {
        String body = register(key).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("externalNumber").asString();
    }

    private ResultActions cancel(String body) throws Exception {
        return mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode bodyOf(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    @Test
    @DisplayName("등록 성공 → 키로 취소 - 200, 활성 등록을 껐고 번호를 알려준다")
    void cancelRegisteredByKey() throws Exception {
        String key = newKey();
        String number = registeredNumber(key);

        JsonNode body = bodyOf(cancel("""
                {"externalKey":"%s","reason":"USER_CANCEL"}""".formatted(key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalKey").value(key))
                .andExpect(jsonPath("$.externalNumbers.length()").value(1))
                .andExpect(jsonPath("$.externalNumbers[0]").value(number))
                .andExpect(jsonPath("$.hadActiveRegistration").value(true))
                .andExpect(jsonPath("$.canceledAt", matchesPattern(UTC_MILLIS))));

        // ERD 의 같은 칸이라 항상 같은 값이다
        assertThat(body.get("cancelMarkerAt")).isEqualTo(body.get("canceledAt"));
        assertThat(repository.findById(key)).get()
                .satisfies(saved -> {
                    assertThat(saved.isCanceled()).isTrue();
                    assertThat(saved.externalNumber()).isEqualTo(number);
                });
    }

    @Test
    @DisplayName("번호만으로 취소 - 그 등록의 키를 찾아 취소한다")
    void cancelByNumberOnly() throws Exception {
        String key = newKey();
        String number = registeredNumber(key);

        cancel("""
                {"externalNumber":"%s"}""".formatted(number))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalKey").value(key))
                .andExpect(jsonPath("$.externalNumbers[0]").value(number))
                .andExpect(jsonPath("$.hadActiveRegistration").value(true));

        assertThat(repository.findById(key)).get().satisfies(saved -> assertThat(saved.isCanceled()).isTrue());
    }

    @Test
    @DisplayName("키와 번호가 같은 등록을 가리키면 그대로 취소한다")
    void cancelByMatchingKeyAndNumber() throws Exception {
        String key = newKey();
        String number = registeredNumber(key);

        cancel("""
                {"externalKey":"%s","externalNumber":"%s"}""".formatted(key, number))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hadActiveRegistration").value(true));
    }

    /**
     * 표식이 없으면 "취소 성공" 이라고 답해놓고 늦게 도착한 등록이 살아난다. 과제의 "항상 성공 가정" 이
     * 거짓이 되는 상황이라, 표식이 실제로 등록을 막는지까지 본다.
     */
    @Test
    @DisplayName("미등록 키 취소 - 200, 표식을 남기고 뒤에 온 등록은 409 다")
    void cancelUnregisteredKeyLeavesMarker() throws Exception {
        String key = newKey();

        cancel("""
                {"externalKey":"%s","reason":"GHOST_COMPENSATION"}""".formatted(key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalKey").value(key))
                .andExpect(jsonPath("$.externalNumbers.length()").value(0))
                .andExpect(jsonPath("$.hadActiveRegistration").value(false))
                .andExpect(jsonPath("$.cancelMarkerAt", matchesPattern(UTC_MILLIS)));

        register(key)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("KEY_CANCELED"));
        assertThat(repository.findById(key)).get()
                .satisfies(saved -> {
                    assertThat(saved.isCanceled()).isTrue();
                    assertThat(saved.externalNumber()).isNull();
                });
    }

    @Test
    @DisplayName("같은 대상 취소 2회 - 둘 다 200, 2회차는 끈 게 없고 취소 시각을 덮어쓰지 않는다")
    void cancelTwice() throws Exception {
        String key = newKey();
        registeredNumber(key);
        String request = """
                {"externalKey":"%s"}""".formatted(key);

        JsonNode first = bodyOf(cancel(request).andExpect(jsonPath("$.hadActiveRegistration").value(true)));
        JsonNode second = bodyOf(cancel(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hadActiveRegistration").value(false)));

        assertThat(second.get("canceledAt")).isEqualTo(first.get("canceledAt"));
        assertThat(second.get("externalNumbers")).isEqualTo(first.get("externalNumbers"));
    }

    @Test
    @DisplayName("미등록 키를 2회 취소해도 표식 하나에 첫 시각 그대로다")
    void cancelUnregisteredTwice() throws Exception {
        String request = """
                {"externalKey":"%s"}""".formatted(newKey());

        JsonNode first = bodyOf(cancel(request));
        JsonNode second = bodyOf(cancel(request).andExpect(status().isOk()));

        assertThat(second.get("cancelMarkerAt")).isEqualTo(first.get("cancelMarkerAt"));
    }

    @Test
    @DisplayName("키와 번호가 둘 다 없으면 400")
    void neitherKeyNorNumber() throws Exception {
        cancel("""
                {"reason":"USER_CANCEL"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errorMessage").value("externalKey 와 externalNumber 중 하나는 있어야 합니다."));
    }

    /** 키가 없으니 표식을 남길 수 없고, 번호는 Mock 이 등록할 때만 발급하므로 늦게 오는 등록도 없다. */
    @Test
    @DisplayName("번호만 받았는데 그 번호의 등록이 없으면 404")
    void unknownNumberOnly() throws Exception {
        cancel("""
                {"externalNumber":"R-19990101-0000000000"}""")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"))
                .andExpect(jsonPath("$.errorMessage").value("등록되지 않았습니다."))
                .andExpect(jsonPath("$.externalNumber").value(nullValue()));
    }

    /**
     * 워커의 버그다. 키만 보고 취소하면 워커는 그 번호를 취소했다고 믿는데 다른 등록이 꺼지고, 그 번호의
     * 등록은 살아남는다. 거절하고 아무것도 바꾸지 않아야 한다.
     */
    @Test
    @DisplayName("키와 번호가 서로 다른 등록을 가리키면 400 이고 아무것도 바꾸지 않는다")
    void keyAndNumberMismatch() throws Exception {
        String keyA = newKey();
        String keyB = newKey();
        registeredNumber(keyA);
        String numberB = registeredNumber(keyB);
        String unregistered = newKey();

        // 키 A 의 번호가 아닌 번호 / 등록 없는 키에 남의 번호
        for (String key : new String[]{keyA, unregistered}) {
            cancel("""
                    {"externalKey":"%s","externalNumber":"%s"}""".formatted(key, numberB))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.errorMessage").value("externalKey 와 externalNumber 가 서로 다른 등록을 가리킵니다."));
        }

        assertThat(repository.findById(keyA)).get().satisfies(saved -> assertThat(saved.isActive()).isTrue());
        assertThat(repository.findById(keyB)).get().satisfies(saved -> assertThat(saved.isActive()).isTrue());
        assertThat(repository.findById(unregistered)).isEmpty();
    }

    /** 키에 등록이 없고 번호도 어디에도 없으면 불일치가 아니다. 키로 표식을 남긴다. */
    @Test
    @DisplayName("등록 없는 키 + 없는 번호 - 키로 표식을 남긴다")
    void unregisteredKeyWithUnknownNumber() throws Exception {
        String key = newKey();

        cancel("""
                {"externalKey":"%s","externalNumber":"R-19990101-0000000000"}""".formatted(key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalNumbers.length()").value(0));

        assertThat(repository.findById(key)).get().satisfies(saved -> assertThat(saved.isCanceled()).isTrue());
    }

    @Test
    @DisplayName("키 · 번호가 비었거나 100자를 넘으면 400")
    void invalidLength() throws Exception {
        cancel("""
                {"externalKey":"   "}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage").value("externalKey 은(는) 1~100자여야 합니다."));
        cancel("""
                {"externalNumber":"%s"}""".formatted("R".repeat(101)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage").value("externalNumber 은(는) 1~100자여야 합니다."));
    }

    /**
     * be 의 예전 계약(기능명세)은 번호를 {@code reservationNo} 로 보냈다. 조용히 버리면 번호 확인 없이
     * 키로만 취소되므로, 모르는 필드로 거절해 계약이 어긋났다는 걸 드러낸다.
     */
    @Test
    @DisplayName("계약에 없는 필드는 400 - 예전 계약의 reservationNo")
    void unknownField() throws Exception {
        cancel("""
                {"externalKey":"%s","reservationNo":null}""".formatted(newKey()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage", containsString("reservationNo 은(는) 알 수 없는 필드입니다.")));
    }

    @Test
    @DisplayName("실패율 100% 에서도 취소는 성공한다 - 취소에는 지연 · 실패를 주입하지 않는다")
    void cancelIgnoresFailureInjection() throws Exception {
        String key = newKey();
        registeredNumber(key);
        store.update(0, 1.0, FailureMode.HTTP_5XX);

        cancel("""
                {"externalKey":"%s"}""".formatted(key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hadActiveRegistration").value(true));
    }
}
