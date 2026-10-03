package com.grandis.nova.mockapi.registration.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.grandis.nova.mockapi.global.chaos.FailureMode;
import com.grandis.nova.mockapi.global.chaos.InMemoryFaultStore;
import com.grandis.nova.mockapi.global.chaos.MockConfigStore;
import com.grandis.nova.mockapi.global.config.MockProperties;
import com.grandis.nova.mockapi.registration.domain.Registration;
import com.grandis.nova.mockapi.registration.domain.RegistrationRepository;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 느린 성공 결함(NV-261)이 기다리는 동안 같은 키로 다른 요청을 끼워 넣는다.
 *
 * <p>등록은 지연 주입 뒤 · 중복 키 재시도 앞(트랜잭션 밖 · 키 행 잠금 전)에서 기다린다. 그래서 기다리는 동안은 행이
 * 없어 키 조회가 404 이고, 같은 키 재시도 · 취소가 먼저 끝난다. 워커가 포기한 뒤에 늦게 커밋되는 등록과, 그때
 * 같은 키 재시도 · 포기 전 취소 표식 규칙이 제대로 막아 내는지를 키 하나로 확실히 본다.
 *
 * <p>결함은 결함 API 로 걸고 실제 보관소가 기다린다. 보관소를 감시만 하는 이유는 원래 요청이 <b>결함을 꺼내고
 * 잠든 뒤</b>에 끼워 넣어야 해서다 — 꺼내기 전에 끼워 넣으면 끼워 넣은 요청이 결함을 가로채 순서가 뒤집힌다.
 *
 * <p>H2 다. 기다리는 동안 DB 에서 겹치는 일이 없어(행이 아직 없다) 잠금 동작에 기대지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SlowSuccessApiTest {

    private static final String RESERVATIONS = "/external/reservations";
    private static final String BODY = """
            {"customerId":1001,"productId":12,"sku":"SM-G999-256-BLK"}""";
    private static final String REPLAY = "X-Idempotent-Replay";
    private static final String INJECTED = "X-Mock-Injected-Latency-Ms";

    /** 끼워 넣을 요청이 끝나기에 넉넉한 시간. 시험 설정은 지연 0 이라 헤더 값이 곧 이 대기 시간이다. */
    private static final long DELAY_MS = 2000;

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

    /** 실제 보관소 그대로 — 원래 요청이 기다리기 시작한 순간만 알아낸다. */
    @MockitoSpyBean
    private InMemoryFaultStore faults;

    private ExecutorService pool;

    @BeforeEach
    void startPool() {
        pool = Executors.newSingleThreadExecutor();
    }

    /** 결함 · 설정을 바꾼 시험은 스스로 되돌린다(팀 규칙). */
    @AfterEach
    void restore() {
        pool.shutdownNow();
        faults.clear();
        store.update(properties.registerLatencyMs(), properties.failureRate(), properties.failureMode());
    }

    /** 인메모리 DB 는 시험 클래스끼리 공유된다. 다른 시험의 행과 겹치지 않게 매번 새로 만든다. */
    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private void injectSlowSuccess(String key) throws Exception {
        mvc.perform(post("/external/faults")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalKey":"%s","faultType":"SLOW_SUCCESS","delayMs":%d}""".formatted(key, DELAY_MS)))
                .andExpect(status().isCreated());
    }

    private MockHttpServletResponse register(String key) throws Exception {
        return mvc.perform(post(RESERVATIONS)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andReturn().getResponse();
    }

    private MockHttpServletResponse lookUp(String key) throws Exception {
        return mvc.perform(get(RESERVATIONS + "/by-key/{externalKey}", key)).andReturn().getResponse();
    }

    private JsonNode bodyOf(MockHttpServletResponse response) throws Exception {
        return json.readTree(response.getContentAsString());
    }

    /**
     * 원래 요청을 따로 띄우고, 그 요청이 결함을 꺼내 잠들 때까지 기다렸다가 돌려준다.
     *
     * <p>보관소에 들어온 것만으로는 아직 결함을 꺼내기 전일 수 있다. 꺼낸 뒤 {@code Thread.sleep} 에 들어가야
     * {@code TIMED_WAITING} 이 되므로, 그 상태를 보고서야 끼워 넣는다.
     */
    private Future<MockHttpServletResponse> registerAndWaitUntilHolding(String key) throws Exception {
        AtomicReference<Thread> holder = new AtomicReference<>();
        CountDownLatch entered = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (holder.compareAndSet(null, Thread.currentThread())) {
                entered.countDown();
            }
            return invocation.callRealMethod();
        }).when(faults).holdBeforeCommit(anyString());

        Future<MockHttpServletResponse> original = pool.submit(() -> register(key));
        assertThat(entered.await(5, TimeUnit.SECONDS))
                .as("등록이 느린 성공 결함을 묻지 않았다 - 등록 처리에서 holdBeforeCommit 을 부르지 않는다")
                .isTrue();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (holder.get().getState() != Thread.State.TIMED_WAITING) {
            assertThat(System.nanoTime()).as("원래 요청이 결함 대기에 들어가지 않았다").isLessThan(until);
            Thread.sleep(5);
        }
        return original;
    }

    @Test
    @DisplayName("기다리는 동안 키 조회는 404 - 기다림이 끝나면 ACTIVE")
    void lookupDuringHold() throws Exception {
        String key = newKey();
        injectSlowSuccess(key);

        Future<MockHttpServletResponse> original = registerAndWaitUntilHolding(key);

        // 잠금 전이라 행이 없다. 워커가 포기하고 이 404 를 보는 순간이 "늦은 커밋" 의 앞쪽이다
        MockHttpServletResponse during = lookUp(key);
        assertThat(during.getStatus()).isEqualTo(404);
        assertThat(bodyOf(during).get("errorCode").asString()).isEqualTo("NOT_FOUND");

        MockHttpServletResponse registered = original.get(10, TimeUnit.SECONDS);
        assertThat(registered.getStatus()).isEqualTo(201);
        assertThat(registered.getHeader(REPLAY)).isEqualTo("false");
        assertThat(registered.getHeader(INJECTED)).isEqualTo(String.valueOf(DELAY_MS));

        JsonNode after = bodyOf(lookUp(key));
        assertThat(after.get("registrations").get(0).get("status").asString()).isEqualTo("ACTIVE");
        assertThat(after.get("storedOutcome").asString()).isEqualTo("SUCCESS");
    }

    /**
     * 기다리는 동안 들어온 같은 키 재시도는 결함이 이미 꺼내져 있어 기다리지 않고 먼저 커밋한다. 원래 요청은 깨어난
     * 뒤 그 등록을 찾아 재생한다 — 늦게 커밋하려던 쪽이 새 등록을 만들면 같은 예약이 두 번 등록된 것이다.
     */
    @Test
    @DisplayName("기다리는 동안 같은 키 재시도 - 재시도가 먼저 새로 등록하고 원래 요청은 깨어나 재생한다. 번호 하나 · 행 하나")
    void retryDuringHold() throws Exception {
        String key = newKey();
        injectSlowSuccess(key);

        Future<MockHttpServletResponse> original = registerAndWaitUntilHolding(key);

        MockHttpServletResponse retry = register(key);
        assertThat(retry.getStatus()).isEqualTo(201);
        assertThat(retry.getHeader(REPLAY)).as("재시도가 먼저 커밋한다").isEqualTo("false");
        assertThat(retry.getHeader(INJECTED)).as("재시도는 기다리지 않았다").isEqualTo("0");
        assertThat(original.isDone()).as("원래 요청은 아직 기다리는 중이다").isFalse();
        String number = bodyOf(retry).get("externalNumber").asString();

        MockHttpServletResponse late = original.get(10, TimeUnit.SECONDS);
        assertThat(late.getStatus()).isEqualTo(201);
        assertThat(late.getHeader(REPLAY)).isEqualTo("true");
        assertThat(late.getHeader(INJECTED)).isEqualTo(String.valueOf(DELAY_MS));
        assertThat(bodyOf(late).get("externalNumber").asString()).isEqualTo(number);

        Registration saved = repository.findById(key).orElseThrow();
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.externalNumber()).isEqualTo(number);
    }

    /** 워커가 포기하며 같은 키로 먼저 취소하면 표식이 남고, 늦게 깨어난 등록은 그 표식에 막힌다. */
    @Test
    @DisplayName("기다리는 동안 같은 키 취소 - 표식이 남고 원래 요청은 깨어나 KEY_CANCELED. 등록 없음")
    void cancelDuringHold() throws Exception {
        String key = newKey();
        injectSlowSuccess(key);

        Future<MockHttpServletResponse> original = registerAndWaitUntilHolding(key);

        MockHttpServletResponse canceled = mvc.perform(post("/external/cancellations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalKey":"%s","reason":"RETRY_EXHAUSTED"}""".formatted(key)))
                .andReturn().getResponse();
        assertThat(canceled.getStatus()).isEqualTo(200);
        assertThat(bodyOf(canceled).get("hadActiveRegistration").asBoolean()).isFalse();
        assertThat(original.isDone()).as("원래 요청은 아직 기다리는 중이다").isFalse();

        MockHttpServletResponse late = original.get(10, TimeUnit.SECONDS);
        assertThat(late.getStatus()).isEqualTo(409);
        assertThat(bodyOf(late).get("errorCode").asString()).isEqualTo("KEY_CANCELED");

        Registration saved = repository.findById(key).orElseThrow();
        assertThat(saved.isCanceled()).isTrue();
        assertThat(saved.externalNumber()).isNull();
    }

    /**
     * 결함을 지연 주입 <b>뒤</b>에서 묻는 이유 — 앞에서 물으면 기다린 뒤 주사위에 걸려 "느린 실패" 가 되고 결함도
     * 그 실패에 써 버린다. 뒤에서 물으면 실패한 요청은 기다리지 않고, 결함은 남아 다음 등록에서 발동한다.
     *
     * <p>실패 응답에도 실제로 기다린 시간이 헤더에 실리므로(#22) 시간을 재지 않고 헤더로 본다.
     */
    @Test
    @DisplayName("실패율에 걸린 요청은 기다리지 않는다 - 결함은 남아 다음 등록에서 발동한다")
    void injectedFailureDoesNotWaitOrConsumeFault() throws Exception {
        String key = newKey();
        injectSlowSuccess(key);
        store.update(0, 1.0, FailureMode.HTTP_5XX);

        MockHttpServletResponse failed = register(key);
        assertThat(failed.getStatus()).isEqualTo(500);
        assertThat(failed.getHeader(INJECTED)).as("실패한 요청은 기다리지 않았다").isEqualTo("0");

        store.update(0, 0.0, FailureMode.HTTP_5XX);
        MockHttpServletResponse registered = registerAndWaitUntilHolding(key).get(10, TimeUnit.SECONDS);
        assertThat(registered.getStatus()).isEqualTo(201);
        assertThat(registered.getHeader(INJECTED)).as("결함이 남아 이번에 기다렸다").isEqualTo(String.valueOf(DELAY_MS));
    }
}
