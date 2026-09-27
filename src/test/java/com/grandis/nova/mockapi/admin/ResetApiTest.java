package com.grandis.nova.mockapi.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.grandis.nova.mockapi.global.chaos.FailureMode;
import com.grandis.nova.mockapi.global.chaos.FaultHook;
import com.grandis.nova.mockapi.global.chaos.FaultType;
import com.grandis.nova.mockapi.global.chaos.InMemoryFaultStore;
import com.grandis.nova.mockapi.global.chaos.MockConfigStore;
import com.grandis.nova.mockapi.global.config.MockProperties;
import com.grandis.nova.mockapi.registration.Registration;
import com.grandis.nova.mockapi.registration.RegistrationRepository;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 기록 초기화 API.
 *
 * <p>초기화는 다음 시험의 전제를 만드는 도구다. 그래서 여기서 보는 것은 "지워졌는가" 뿐만 아니라
 * <b>거절했을 때 아무것도 지우지 않는가</b>와 <b>설정을 남기는가</b>다. 설정까지 지우면 시연 중
 * 조작 패널을 매번 다시 채워야 한다.
 *
 * <p>다른 시험 클래스가 남긴 등록 행이 여기 건수 단정을 깨뜨리므로 {@code @BeforeEach} 에서 먼저
 * 비운다. H2 는 같은 JVM 안에서 컨텍스트가 달라도 같은 메모리 DB 를 쓴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ResetApiTest {

    private static final String PATH = "/external/reset";
    private static final String CONFIRMED = """
            {"confirm":"RESET"}""";
    private static final String KEY = "reset-test-1";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private RegistrationRepository repository;

    /** 지우기와 결함 비우기의 순서를 보려면 {@code clear()} 가 불리는 순간을 가로채야 한다. */
    @MockitoSpyBean
    private InMemoryFaultStore faults;

    @Autowired
    private FaultHook faultHook;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockConfigStore config;

    @Autowired
    private MockProperties properties;

    /**
     * 남의 시험이 남긴 행과 결함을 먼저 비운다. 초기화 시험은 "0건" 을 단정하므로 시작 상태를
     * 스스로 정해야 한다.
     */
    @BeforeEach
    void emptyEverything() {
        repository.deleteAll();
        faults.clear();
    }

    /**
     * 설정을 바꾼 시험은 스스로 되돌린다(팀 규칙). {@code @SpringBootTest} 는 컨텍스트를 캐시하고
     * 저장소는 싱글턴이라, 되돌리지 않으면 바뀐 설정이 다음 시험 클래스까지 따라간다.
     */
    @AfterEach
    void restoreDefaults() {
        config.update(properties.registerLatencyMs(), properties.failureRate(), properties.failureMode());
    }

    private ResultActions reset(String body) throws Exception {
        return mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** 번호는 UNIQUE 라 키로 만들어 겹치지 않게 한다. 형식은 이 시험과 무관하다. */
    private void saveActive(String key) {
        repository.save(Registration.active(key, "R-" + key, 1001L, 12L,
                "SM-G999-256-BLK", Instant.now()));
    }

    /**
     * 등록 행과 취소 표식은 같은 표의 행이다. 둘을 합한 수가 나와야 명세의 "등록·멱등·취소 기록을
     * 지운다" 가 한 번에 지켜진 것이다.
     */
    @Test
    @DisplayName("등록 행과 취소 표식을 함께 지우고 그 수를 돌려준다")
    void deletesRegistrationsAndCancelMarkers() throws Exception {
        saveActive("reset-active-1");
        saveActive("reset-active-2");
        repository.save(Registration.cancelMarker("reset-canceled-1", Instant.now()));

        reset(CONFIRMED)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedCount").value(3));

        assertThat(repository.count()).isZero();
    }

    @Test
    @DisplayName("지울 것이 없으면 0 이다")
    void emptyIsZero() throws Exception {
        reset(CONFIRMED)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedCount").value(0));
    }

    /**
     * 거절이 지우기까지 해버리면 확인 문자열이 아무것도 막지 못한다. 400 만 보고 끝내면 이 실수를
     * 놓친다.
     */
    @Test
    @DisplayName("확인 문자열이 틀리면 400 이고 기록을 건드리지 않는다")
    void rejectsWithoutDeleting() throws Exception {
        saveActive(KEY);

        reset("""
                {"confirm":"reset"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errorMessage").value("confirm 은(는) RESET 이어야 합니다."));

        reset("{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage").value("confirm 은(는) 필수입니다."));

        reset("""
                {"confirm":"RESETX"}""")
                .andExpect(status().isBadRequest());

        reset("""
                {"confirm":"RESET","scope":"all"}""")
                .andExpect(status().isBadRequest());

        assertThat(repository.count()).isEqualTo(1);
    }

    /**
     * 명세: "지연·실패 설정과 설정 버전은 그대로 둔다."
     *
     * <p>기본값과 비교하면 초기화가 설정을 되돌렸는지 알 수 없으므로, 먼저 기본값과 다른 값으로
     * 바꿔 놓고 본다. {@code configVersion} 은 누적이라 초기화가 올리거나 되돌리면 드러난다.
     */
    @Test
    @DisplayName("초기화는 설정과 설정 버전을 건드리지 않는다")
    void keepsConfig() throws Exception {
        config.update(2000, 0.5, FailureMode.TIMEOUT);
        var before = config.applied();

        reset(CONFIRMED).andExpect(status().isOk());

        var after = config.applied();
        assertThat(after.snapshot()).isEqualTo(before.snapshot());
        assertThat(after.appliedAt()).isEqualTo(before.appliedAt());
    }

    /**
     * 실패율을 1.0 으로 올려둔 상태에서도 초기화할 수 있어야 한다. 이 경로에 지연·실패를 주입하면
     * 시연 중 Mock 을 되돌릴 방법이 사라진다.
     */
    @Test
    @DisplayName("실패율 1.0 에서도 초기화된다 — 이 경로에는 실패를 주입하지 않는다")
    void worksWhileEverythingFails() throws Exception {
        config.update(0, 1.0, FailureMode.HTTP_5XX);

        reset(CONFIRMED).andExpect(status().isOk());
    }

    /**
     * 발동하지 않고 남은 결함을 지우지 않으면, 다음 시험에서 결함을 걸지 않은 키의 응답이 유실되고
     * 원인을 찾기 어렵다.
     */
    @Test
    @DisplayName("발동하지 않은 결함도 함께 지운다")
    void clearsPendingFaults() throws Exception {
        faults.inject(KEY, FaultType.RESPONSE_LOST_AFTER_COMMIT);

        reset(CONFIRMED).andExpect(status().isOk());

        assertThat(faults.consumeResponseLost(KEY)).isFalse();
    }

    /**
     * 초기화가 비우는 보관소와 등록 처리가 물어보는 보관소가 다른 객체면, 초기화해도 결함이 살아
     * 있는데 아무도 모른다. 스텁이 되살아나는 것도 여기서 걸린다.
     */
    @Test
    @DisplayName("등록 처리가 보는 FaultHook 이 초기화가 비우는 그 보관소다")
    void faultHookIsTheStore() {
        assertThat(faultHook).isSameAs(faults);
    }

    /**
     * 결함을 마지막에 지운다는 약속이 실제로 지켜지는지 본다.
     *
     * <p>{@code deleteAll()} 은 행을 읽어와 표시만 하고 DELETE 를 커밋까지 미룬다. 그러면 코드
     * 순서는 맞는데 <b>실제로는 결함이 먼저 사라진다.</b> 리뷰에서 잡힌 문제이고, 눈으로는 보이지
     * 않아 여기서 명시적으로 확인한다.
     *
     * <p>JPA 가 아니라 {@link JdbcTemplate} 으로 센다. 같은 트랜잭션·같은 커넥션이면서 영속성
     * 컨텍스트의 flush 를 유발하지 않아, 그 시점에 DB 에 실제로 남아 있는 행만 보인다.
     */
    @Test
    @DisplayName("결함을 비우는 시점에 등록 행은 이미 지워져 있다")
    void rowsAreGoneBeforeFaultsAreCleared() throws Exception {
        saveActive("reset-order-1");
        saveActive("reset-order-2");
        long[] rowsWhenCleared = new long[1];
        doAnswer(invocation -> {
            rowsWhenCleared[0] = jdbc.queryForObject(
                    "select count(*) from preorder_registrations", Long.class);
            return invocation.callRealMethod();
        }).when(faults).clear();

        reset(CONFIRMED).andExpect(status().isOk());

        assertThat(rowsWhenCleared[0]).isZero();
    }
}
