package com.grandis.nova.mockapi.control.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.grandis.nova.mockapi.global.chaos.ConfigProvider;
import com.grandis.nova.mockapi.global.chaos.FailureMode;
import com.grandis.nova.mockapi.global.chaos.MockConfigStore;
import com.grandis.nova.mockapi.global.config.MockProperties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 지연·실패 설정 API.
 *
 * <p>이 API 는 시연 조작 패널이다. 워커가 부르지 않으므로 잘못된 입력은 전부 400 이어야 하고,
 * 거절한 요청이 현재 설정을 건드려서도 안 된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MockConfigApiTest {

    private static final String PATH = "/external/config";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MockConfigStore store;

    @Autowired
    private ConfigProvider configProvider;

    @Autowired
    private MockProperties properties;

    /**
     * 바꾼 설정을 기본값으로 되돌린다.
     *
     * <p>{@code @SpringBootTest} 는 컨텍스트를 캐시해 재사용하고 저장소는 싱글턴이라, 되돌리지
     * 않으면 바뀐 설정이 다음 시험 클래스까지 따라간다. 지연·실패 주입이 올라오면 남의 시험이
     * 무작위로 느려지고 실패하는데, 원인을 찾기 어려운 종류다.
     *
     * <p>{@code configVersion} 은 누적이라 0 으로 되돌릴 수 없다. 여기 시험들은 모두 상대 비교
     * (before + 1, before + N)라 문제되지 않는다.
     */
    @AfterEach
    void restoreDefaults() {
        store.update(properties.registerLatencyMs(), properties.failureRate(), properties.failureMode());
    }

    /**
     * 스텁이 안 물러나면 등록 파트는 설정을 바꿔도 계속 기본값으로 돈다.
     *
     * <p>오류도 나지 않고 다른 시험도 통과하는, 조용히 잘못되는 종류라 명시적으로 확인한다.
     */
    @Test
    @DisplayName("실제 구현이 올라오면 임시 스텁은 물러난다")
    void stubBacksOff() {
        assertThat(configProvider).isSameAs(store);
    }

    /**
     * 앞 시험이 바꾼 설정이 남아 있지 않은지 본다. 복원이 빠지면 여기서 걸린다.
     */
    @Test
    @DisplayName("시험은 언제나 기본값에서 시작한다")
    void startsFromDefaults() {
        var snapshot = store.snapshot();
        assertThat(snapshot.registerLatencyMs()).isEqualTo(properties.registerLatencyMs());
        assertThat(snapshot.failureRate()).isEqualTo(properties.failureRate());
        assertThat(snapshot.failureMode()).isEqualTo(properties.failureMode());
    }

    /**
     * PUT 응답만 보면 "받은 값을 그대로 돌려준 것" 과 구분할 수 없다. GET 으로 다시 확인해야
     * 실제로 저장됐다는 증거가 된다.
     */
    @Test
    @DisplayName("변경한 값이 그대로 조회된다")
    void updateThenGet() throws Exception {
        mvc.perform(put(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"registerLatencyMs":2000,"failureRate":0.5,"failureMode":"TIMEOUT"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registerLatencyMs").value(2000))
                .andExpect(jsonPath("$.failureRate").value(0.5))
                .andExpect(jsonPath("$.failureMode").value("TIMEOUT"));

        mvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registerLatencyMs").value(2000))
                .andExpect(jsonPath("$.failureRate").value(0.5))
                .andExpect(jsonPath("$.failureMode").value("TIMEOUT"))
                .andExpect(jsonPath("$.appliedAt").exists());
    }

    /**
     * 범위 검사는 API 가 유일한 방어선이다. ERD 가 설정 테이블을 두지 않기로 해서 DB 제약이 없다.
     *
     * <p>거절한 요청이 현재 설정을 건드리지 않는 것까지 본다. 검증이 update 보다 먼저 돌지 않으면
     * "저장은 해놓고 400 을 주는" 상태가 된다.
     *
     * <p>기본형(int·double)으로 받으면 필드 누락이 0 으로 들어와 "지연 0ms · 실패율 0%" 가 조용히
     * 적용된다. 그래서 래퍼형 + {@code @NotNull} 로 받는다.
     */
    @Test
    @DisplayName("범위 밖 값과 필드 누락은 400 이고, 현재 설정을 바꾸지 않는다")
    void rejectsInvalid() throws Exception {
        store.update(700, 0.3, FailureMode.HTTP_5XX);
        var before = store.applied();

        for (String body : new String[]{
                """
                {"registerLatencyMs":-1,"failureRate":0.5}""",
                """
                {"registerLatencyMs":60001,"failureRate":0.5}""",
                """
                {"registerLatencyMs":500,"failureRate":1.5}""",
                """
                {"registerLatencyMs":500,"failureRate":-0.1}""",
                """
                {"registerLatencyMs":500}""",
                """
                {"failureRate":0.5}"""}) {
            mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        }

        assertThat(store.applied()).isEqualTo(before);
    }

    /**
     * {@code timeoutHoldMs} 는 설정 API 의 필드가 아니라 환경변수다. 워커 읽기 타임아웃에 맞춰
     * 기동할 때 정하는 값이라 실행 중에 바꾸면 그 순간 붙잡고 있던 요청과 어긋난다.
     *
     * <p>조용히 버리면 <b>보낸 사람은 적용된 줄 안다.</b> 유지 시간이 워커 타임아웃보다 짧으면
     * 워커가 타임아웃 대신 500 을 받아 재현하려던 상황이 아니게 되는데, 설정을 바꿨다고 믿는
     * 사람은 그 원인을 여기서 찾지 못한다.
     */
    @Test
    @DisplayName("timeoutHoldMs 는 설정 API 로 바꿀 수 없다 — 400 이고 설정도 그대로다")
    void rejectsTimeoutHoldMs() throws Exception {
        var before = store.applied();

        mvc.perform(put(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"registerLatencyMs":500,"failureRate":0.05,"timeoutHoldMs":9000}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errorMessage",
                        containsString("timeoutHoldMs 은(는) 알 수 없는 필드입니다.")));

        assertThat(store.applied()).isEqualTo(before);
    }

    /**
     * 지연이 평균이라 실제 대기가 얼마나 흔들리는지 알아야 결과를 읽을 수 있다. 부하 하네스가 이
     * 값으로 주입한 지연의 백분위를 계산하고, 요구사항 5.4 대로 적용한 설정을 기록한다.
     */
    @Test
    @DisplayName("조회 · 변경 응답에 지연 지터가 함께 나간다")
    void exposesLatencyJitter() throws Exception {
        mvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latencyJitter").value(properties.latencyJitter()));

        mvc.perform(put(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"registerLatencyMs":500,"failureRate":0.05}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latencyJitter").value(properties.latencyJitter()));
    }

    /**
     * 지터는 설정 파일로만 정한다. 실행 중에 바꿀 수 있으면 등록 파트와의 계약인 {@code ConfigSnapshot}
     * 이 늘어나고, 부하 판정 도중 분포가 바뀌면 주입한 몫을 빼낼 수 없다.
     */
    @Test
    @DisplayName("latencyJitter 는 설정 API 로 바꿀 수 없다 — 400 이고 설정도 그대로다")
    void rejectsLatencyJitter() throws Exception {
        var before = store.applied();

        mvc.perform(put(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"registerLatencyMs":500,"failureRate":0.05,"latencyJitter":0.0}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errorMessage",
                        containsString("latencyJitter 은(는) 알 수 없는 필드입니다.")));

        assertThat(store.applied()).isEqualTo(before);
    }

    @Test
    @DisplayName("failureMode 를 생략하면 HTTP_5XX 다")
    void failureModeDefaults() throws Exception {
        mvc.perform(put(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"registerLatencyMs":500,"failureRate":0.05}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failureMode").value("HTTP_5XX"));
    }

    @Test
    @DisplayName("잘못된 failureMode 는 400 이다")
    void rejectsUnknownFailureMode() throws Exception {
        mvc.perform(put(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"registerLatencyMs":500,"failureRate":0.5,"failureMode":"HTTP_500"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("변경할 때마다 configVersion 이 오른다")
    void versionIncreases() throws Exception {
        int before = store.snapshot().configVersion();

        mvc.perform(put(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"registerLatencyMs":100,"failureRate":0.1}"""))
                .andExpect(jsonPath("$.configVersion").value(before + 1));
    }

    /**
     * 설정을 동시에 바꿀 일은 드물지만, 버전이 겹치면 "이 등록 시도가 어느 설정으로 돌았나" 를
     * 추적할 수 없게 된다. 일반 필드로 두면 읽고 +1 해서 쓰는 사이에 서로 덮어써 건수보다 덜 오른다.
     */
    @Test
    @DisplayName("동시에 바꿔도 configVersion 이 건수만큼 오른다")
    void versionIsAtomicUnderConcurrency() throws Exception {
        int threads = 50;
        int before = store.snapshot().configVersion();
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    store.update(500, 0.05, FailureMode.HTTP_5XX);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(store.snapshot().configVersion()).isEqualTo(before + threads);
    }
}
