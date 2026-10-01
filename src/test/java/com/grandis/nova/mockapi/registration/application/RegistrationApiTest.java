package com.grandis.nova.mockapi.registration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.grandis.nova.mockapi.global.chaos.FailureInjector;
import com.grandis.nova.mockapi.global.chaos.FailureMode;
import com.grandis.nova.mockapi.global.chaos.InMemoryFaultStore;
import com.grandis.nova.mockapi.global.chaos.MockConfigStore;
import com.grandis.nova.mockapi.global.config.MockProperties;
import com.grandis.nova.mockapi.registration.domain.ExternalNumberGenerator;
import com.grandis.nova.mockapi.registration.domain.Registration;
import com.grandis.nova.mockapi.registration.domain.RegistrationRepository;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 예약 등록 API. 명세 v5 의 확인 시나리오를 HTTP 로 본다.
 *
 * <p>H2 라 동시성은 여기서 보지 않는다. 같은 키 동시 요청은 {@link RegistrationConcurrencyTest} 가
 * 진짜 MySQL 로 본다. 여기서는 순서대로 부를 때의 판정이 명세와 같은지만 본다.
 *
 * <p>응답 유실 시험이 붙잡는 시간을 기다리지 않게 유지 시간을 0 으로 둔다. 이 클래스는
 * {@code @MockitoBean} 때문에 어차피 전용 컨텍스트라 캐시 비용이 늘지 않는다.
 */
@SpringBootTest(properties = "mock.timeout-hold-ms=0")
@AutoConfigureMockMvc
class RegistrationApiTest {

    private static final String PATH = "/external/reservations";
    private static final String BODY = """
            {"customerId":1001,"productId":12,"sku":"SM-G999-256-BLK"}""";

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

    @MockitoSpyBean
    private ExternalNumberGenerator numbers;

    @MockitoSpyBean
    private FailureInjector failureInjector;

    /**
     * 결함이 걸렸는지는 시험이 정한다. 발동했을 때 7단계가 제대로 이어지는지 본다.
     *
     * <p>{@code FaultHook} 이 아니라 구현 클래스로 받는다. 인터페이스로 받으면 그 빈이 인터페이스만
     * 아는 목으로 교체되어, 같은 빈을 구현 클래스로 주입받는 결함 주입 API 가 뜨지 못한다.
     */
    @MockitoBean
    private InMemoryFaultStore faultHook;

    /** 설정·결함을 바꾼 시험은 스스로 되돌린다(팀 규칙). */
    @AfterEach
    void restoreDefaults() {
        store.update(properties.registerLatencyMs(), properties.failureRate(), properties.failureMode());
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private ResultActions register(String key, String body) throws Exception {
        return mvc.perform(post(PATH)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private JsonNode bodyOf(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    @Test
    @DisplayName("최초 등록 - 201, 새 번호, 재생 아님, 시각은 UTC Z")
    void firstRegistration() throws Exception {
        String key = newKey();
        int version = store.snapshot().configVersion();

        register(key, BODY)
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Idempotent-Replay", "false"))
                .andExpect(header().string("X-Mock-Config-Version", String.valueOf(version)))
                .andExpect(jsonPath("$.externalKey").value(key))
                .andExpect(jsonPath("$.externalNumber", matchesPattern("R-\\d{8}-\\d{10}")))
                .andExpect(jsonPath("$.customerId").value(1001))
                .andExpect(jsonPath("$.productId").value(12))
                .andExpect(jsonPath("$.sku").value("SM-G999-256-BLK"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.confirmedAt", matchesPattern(".+Z")))
                // 값이 없어도 필드를 생략하지 않는다
                .andExpect(jsonPath("$.canceledAt").value(nullValue()));
    }

    @Test
    @DisplayName("같은 키 · 같은 내용 2회 - 같은 번호, 2회차는 재생이고 응답이 한 글자도 다르지 않다")
    void replaySameContent() throws Exception {
        String key = newKey();

        JsonNode first = bodyOf(register(key, BODY).andExpect(header().string("X-Idempotent-Replay", "false")));
        JsonNode second = bodyOf(register(key, BODY)
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Idempotent-Replay", "true")));

        // 첫 응답은 메모리의 값, 재생은 DB 에서 읽은 값이다. 시각 정밀도가 다르면 여기서 어긋난다.
        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("같은 키 · 다른 sku - 422, 기존 번호를 알려주고 기존 등록은 그대로다")
    void payloadMismatch() throws Exception {
        String key = newKey();
        String number = bodyOf(register(key, BODY)).get("externalNumber").asString();

        register(key, """
                {"customerId":1001,"productId":12,"sku":"SM-G999-512-WHT"}""")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("KEY_PAYLOAD_MISMATCH"))
                .andExpect(jsonPath("$.errorMessage").value("같은 키로 다른 내용이 요청되었습니다. sku 이(가) 다릅니다."))
                .andExpect(jsonPath("$.replayable").value(false))
                .andExpect(jsonPath("$.externalNumber").value(number));

        assertThat(repository.findById(key)).get()
                .satisfies(saved -> assertThat(saved.sku()).isEqualTo("SM-G999-256-BLK"));
    }

    @Test
    @DisplayName("다른 칸이 여럿이면 전부 알려준다")
    void payloadMismatchListsEveryField() throws Exception {
        String key = newKey();
        register(key, BODY);

        register(key, """
                {"customerId":2002,"productId":12,"sku":"SM-G999-512-WHT"}""")
                .andExpect(jsonPath("$.errorMessage", containsString("customerId, sku 이(가) 다릅니다.")));
    }

    @Test
    @DisplayName("등록 전 취소 표식이 있는 키 - 409, 등록을 만들지 않는다")
    void cancelMarkerBlocksRegistration() throws Exception {
        String key = newKey();
        repository.saveAndFlush(Registration.cancelMarker(key, Instant.now()));

        register(key, BODY)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("KEY_CANCELED"))
                .andExpect(jsonPath("$.replayable").value(true))
                .andExpect(jsonPath("$.externalNumber").value(nullValue()));

        assertThat(repository.findById(key)).get()
                .satisfies(saved -> {
                    assertThat(saved.isCanceled()).isTrue();
                    assertThat(saved.externalNumber()).isNull();
                });
    }

    @Test
    @DisplayName("등록 → 취소 → 같은 키 재등록 - 201 재생이 아니라 409 다")
    void registerCancelRegister() throws Exception {
        String key = newKey();
        String number = bodyOf(register(key, BODY)).get("externalNumber").asString();
        mvc.perform(post("/external/cancellations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalKey":"%s"}""".formatted(key)))
                .andExpect(status().isOk());

        register(key, BODY)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("KEY_CANCELED"))
                .andExpect(jsonPath("$.externalNumber").value(number));
    }

    /**
     * 실패를 저장해 버리는 버그는 5% 로는 드러나지 않는다. 100% 로 돌려야 0% 로 내린 뒤에도
     * 저장된 실패가 재생되는지 보인다.
     */
    @Test
    @DisplayName("실패율 1.0 으로 10회 실패 → 0.0 으로 내리면 즉시 성공한다. 실패는 저장되지 않는다")
    void failuresAreNotStored() throws Exception {
        String key = newKey();
        store.update(0, 1.0, FailureMode.HTTP_5XX);

        for (int i = 0; i < 10; i++) {
            register(key, BODY)
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.errorCode").value("UPSTREAM_UNAVAILABLE"));
        }
        assertThat(repository.findById(key)).isEmpty();

        store.update(0, 0.0, FailureMode.HTTP_5XX);
        register(key, BODY)
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Idempotent-Replay", "false"));
    }

    @Test
    @DisplayName("응답의 설정 버전은 이 시도가 시작할 때 얼린 값이다")
    void configVersionHeader() throws Exception {
        int changed = store.update(0, 0.0, FailureMode.HTTP_5XX).snapshot().configVersion();

        register(newKey(), BODY)
                .andExpect(header().string("X-Mock-Config-Version", String.valueOf(changed)));
    }

    /**
     * 지연을 트랜잭션 안에서 기다리면 그 시간만큼 DB 커넥션이 묶여 풀이 마른다. 누가 서비스에
     * {@code @Transactional} 을 붙이면 오류 없이 부하에서만 드러나므로 명시적으로 본다.
     */
    @Test
    @DisplayName("지연 · 실패 주입은 트랜잭션 밖에서 불린다")
    void failureInjectionRunsOutsideTransaction() throws Exception {
        AtomicBoolean inTransaction = new AtomicBoolean(true);
        doAnswer(invocation -> {
            inTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            return invocation.callRealMethod();
        }).when(failureInjector).apply(any());

        register(newKey(), BODY).andExpect(status().isCreated());

        assertThat(inTransaction).isFalse();
    }

    /**
     * 잠금 읽기로 "없음" 을 본 직후 다른 요청이 같은 키를 커밋하면, 저장이 merge(조회 후 갱신)로 가는
     * 순간 남의 등록을 덮어쓴다. 그 틈은 동시성 시험으로는 거의 걸리지 않을 만큼 짧아서, 그 상황을
     * 직접 만들어 본다 — 이미 있는 키로 새 등록을 저장하면 덮어쓰지 않고 중복 키로 떨어져야 한다.
     */
    @Test
    @DisplayName("이미 있는 키로 새 등록을 저장하면 덮어쓰지 않고 중복 키로 떨어진다")
    void newRegistrationNeverOverwrites() {
        String key = newKey();
        repository.saveAndFlush(Registration.active(key, "R-19990101-1000000001", 1L, 1L, "A", Instant.now()));

        assertThatThrownBy(() -> repository.saveAndFlush(
                Registration.active(key, "R-19990101-1000000002", 2L, 2L, "B", Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(DuplicateKey.isCause(e)).isTrue());

        assertThat(repository.findById(key)).get()
                .satisfies(saved -> assertThat(saved.externalNumber()).isEqualTo("R-19990101-1000000001"));
    }

    /** 번호는 난수라 드물게 겹친다. 겹치면 새 트랜잭션에서 새 번호로 다시 시도해야 한다. */
    @Test
    @DisplayName("번호가 겹치면 새 번호로 다시 시도한다 - 같은 번호로 다시 부딪히지 않는다")
    void retriesWithNewNumberOnCollision() throws Exception {
        doReturn("R-19990101-0000000001")          // 첫 키
                .doReturn("R-19990101-0000000001") // 둘째 키 첫 시도 — 겹친다
                .doReturn("R-19990101-0000000002") // 둘째 키 재시도
                .when(numbers).next(any());

        register(newKey(), BODY).andExpect(jsonPath("$.externalNumber").value("R-19990101-0000000001"));
        register(newKey(), BODY)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.externalNumber").value("R-19990101-0000000002"));

        verify(numbers, times(3)).next(any());
    }

    /**
     * 상한을 넘기면 워커는 똑같이 재시도하지만, 부하 시험 결과에서 "주입한 실패" 와 구분돼야 한다.
     * 그래서 기본 문장이 아니라 상한 초과라고 적힌 500 이 나가야 한다.
     */
    @Test
    @DisplayName("번호가 계속 겹쳐 재시도 상한을 넘기면 상한 초과라고 적힌 500 이다")
    void retryExhaustedIsNamed() throws Exception {
        doReturn("R-19990101-0000000009").when(numbers).next(any());   // 매번 같은 번호
        register(newKey(), BODY).andExpect(status().isCreated());

        String key = newKey();
        register(key, BODY)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("UPSTREAM_UNAVAILABLE"))
                .andExpect(jsonPath("$.errorMessage").value(DuplicateKeyRetry.RETRY_EXHAUSTED));

        // 첫 키 1번 + 둘째 키 상한만큼
        verify(numbers, times(1 + DuplicateKeyRetry.MAX_ATTEMPTS)).next(any());
        assertThat(repository.findById(key)).isEmpty();
    }

    /**
     * 명세 시나리오 "RESPONSE_LOST_AFTER_COMMIT 주입 후 재시도 → 저장된 성공 재생. 등록 1건".
     * 결함은 새로 커밋한 요청에만 걸리고, 재생에는 걸리지 않는다.
     *
     * <p><b>여기서는 워커가 결과를 모르는지는 증명하지 않는다.</b> MockMvc 에는 실제 소켓이 없어 연결을
     * 붙잡다 끝낸 응답이 500 으로 보인다. 워커가 정말 읽기 타임아웃(UNKNOWN)을 겪는지는
     * {@code ConnectionDropperE2eTest} 가 실제 HTTP 클라이언트로 본다. 이 500 을 고치려 들지 말 것.
     */
    @Test
    @DisplayName("커밋 후 응답 유실 - 등록은 남고, 재시도는 재생된다")
    void responseLostAfterCommitThenReplay() throws Exception {
        String key = newKey();
        when(faultHook.consumeResponseLost(anyString())).thenReturn(true);

        // 연결 끊기로 들어갔다는 것만 본다 — 오류 본문 없이 끝났다
        register(key, BODY)
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(""));
        assertThat(repository.findById(key)).get()
                .satisfies(saved -> assertThat(saved.isActive()).isTrue());

        register(key, BODY)
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Idempotent-Replay", "true"));
        // 재생 요청에서는 결함을 묻지도 않는다
        verify(faultHook, times(1)).consumeResponseLost(key);
    }

    @Test
    @DisplayName("Idempotency-Key 가 없으면 400")
    void missingKey() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage").value("Idempotency-Key 헤더가 필요합니다."));
    }

    @Test
    @DisplayName("Idempotency-Key 가 비었거나 100자를 넘으면 400")
    void invalidKeyLength() throws Exception {
        for (String key : new String[]{"   ", "k".repeat(101)}) {
            register(key, BODY)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorMessage").value("Idempotency-Key 은(는) 1~100자여야 합니다."));
        }
        register("k".repeat(100), BODY).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("본문 검증 - 필드 이름을 담아 400")
    void invalidBody() throws Exception {
        register(newKey(), """
                {"customerId":1001,"productId":12}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage").value("sku 은(는) 필수입니다."));

        register(newKey(), """
                {"customerId":1001,"productId":12,"sku":"%s"}""".formatted("S".repeat(81)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage").value("sku 은(는) 80자 이하여야 합니다."));

        // 예전 README 계약의 필드. 계약에 없는 필드는 거절한다
        register(newKey(), """
                {"customerId":1001,"productId":12,"sku":"SM-G999-256-BLK","quantity":1}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage", containsString("quantity 은(는) 알 수 없는 필드입니다.")));
    }

    /**
     * 4xx 는 "확정 거절" 이라 워커는 등록이 안 됐다고 믿는다. 매핑에서 걸러지지 않으면 등록을 커밋한 뒤에야
     * 응답을 못 써 406 이 나가, 된 등록을 안 된 것으로 믿게 된다.
     */
    @Test
    @DisplayName("Accept: text/plain 등록 - 아무것도 남기지 않고 JSON 본문의 400")
    void acceptWithoutJsonLeavesNothing() throws Exception {
        String key = newKey();

        mvc.perform(post(PATH)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_PLAIN)
                        .content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errorMessage", containsString("Accept")));

        assertThat(repository.findById(key)).isEmpty();
    }

    /** 와일드카드 Content-Type 과 form 본문(curl {@code -d} 의 기본값)은 예전에 500 이었다. 500 은 재시도 대상이다. */
    @Test
    @DisplayName("JSON 이 아닌 Content-Type - 와일드카드 · form 본문 모두 400, 아무것도 남기지 않는다")
    void nonJsonContentTypeIsBadRequest() throws Exception {
        String wildcard = newKey();
        mvc.perform(post(PATH)
                        .header("Idempotency-Key", wildcard)
                        .contentType("application/*")
                        .content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errorMessage", containsString("Content-Type")));

        String form = newKey();
        mvc.perform(post(PATH)
                        .header("Idempotency-Key", form)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("sku=100%"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));

        assertThat(repository.findById(wildcard)).isEmpty();
        assertThat(repository.findById(form)).isEmpty();
    }
}
