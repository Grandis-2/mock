package com.grandis.nova.mockapi.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.grandis.nova.mockapi.global.chaos.FailureInjector;
import com.grandis.nova.mockapi.global.error.ErrorCode;
import com.grandis.nova.mockapi.global.error.MockException;
import com.grandis.nova.mockapi.registration.dto.RegisterRequest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * 같은 키 동시 요청을 <b>진짜 MySQL</b> 로 본다.
 *
 * <p>멱등 등록은 잠금 읽기와 중복 키(1062)에 기댄다. H2 는 둘 다 다르게 동작하므로 여기서 통과해야
 * 의미가 있다. 키 조회가 진행 중인 등록을 기다리는지, 등록과 취소가 엇갈려도 취소가 이기는지도
 * 잠금 · 중복 키 동작이라 여기서 본다. 스키마는
 * {@code docs/schema.sql} 을 그대로 넣고({@code ddl-auto=validate} 로 엔티티와 맞는지도 확인된다),
 * 서버 격리 수준은 compose 와 같은 READ COMMITTED 다.
 *
 * <p>Docker 가 없으면 이 시험만 건너뛴다. 빌드는 막지 않는다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.datasource.hikari.transaction-isolation=TRANSACTION_READ_COMMITTED",
        "spring.datasource.hikari.maximum-pool-size=20"
})
@AutoConfigureMockMvc
class RegistrationConcurrencyTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("external_mock")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("docs/schema.sql"), "/docker-entrypoint-initdb.d/01-schema.sql")
            .withCommand(
                    "--transaction-isolation=READ-COMMITTED",
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci");

    private static final RegisterRequest REQUEST = new RegisterRequest(1001L, 12L, "SM-G999-256-BLK");

    /** 동시성 버그는 경합에서만 나온다. 한 번 통과는 증명이 아니다(명세: 최소 100회). */
    private static final int ROUNDS = 100;
    private static final int CONCURRENT = 10;

    /** 키 조회가 이만큼 돌아오지 않으면 등록을 기다리는 중으로 본다. 잠금 없는 조회는 몇 ms 면 끝난다. */
    private static final long LOCK_WAIT_PROOF_MS = 500;

    /** MySQL 이 DATETIME(6) 을 글자로 내줄 때의 모양. */
    private static final DateTimeFormatter DB_DATETIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS").withZone(ZoneOffset.UTC);

    @Autowired
    private RegistrationService service;

    @Autowired
    private RegistrationRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RegistrationWriter writer;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private CancellationService cancellations;

    @MockitoSpyBean
    private ExternalNumberGenerator numbers;

    /** 등록을 지연 구간에서 붙잡는 데 쓴다. 붙잡지 않는 시험에서는 원래대로 동작한다. */
    @MockitoSpyBean
    private FailureInjector failureInjector;

    @Test
    @DisplayName("커넥션 격리 수준이 READ COMMITTED 다 — 1062 를 정상 분기로 다루는 설계의 전제")
    void readCommitted() {
        assertThat(jdbc.queryForObject("SELECT @@transaction_isolation", String.class))
                .isEqualTo("READ-COMMITTED");
    }

    @Test
    @DisplayName("같은 키 동시 10건 × 100회 - 매번 등록 1건, 번호 1개, 나머지 9건은 재생")
    void sameKeyConcurrently() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            String key = UUID.randomUUID().toString();
            List<RegisterResult> results = registerConcurrently(key);

            assertThat(results).as("round %d", round)
                    .filteredOn(result -> !result.replayed()).hasSize(1);
            assertThat(results).as("round %d", round)
                    .extracting(result -> result.registration().externalNumber())
                    .containsOnly(results.getFirst().registration().externalNumber());
            assertThat(repository.findById(key)).as("round %d", round).get()
                    .satisfies(saved -> {
                        assertThat(saved.isActive()).isTrue();
                        assertThat(saved.externalNumber())
                                .isEqualTo(results.getFirst().registration().externalNumber());
                    });
        }
    }

    private List<RegisterResult> registerConcurrently(String key) throws Exception {
        var start = new CountDownLatch(1);
        List<Future<RegisterResult>> futures = new ArrayList<>(CONCURRENT);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < CONCURRENT; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return service.register(key, REQUEST);
                }));
            }
            start.countDown();
            List<RegisterResult> results = new ArrayList<>(CONCURRENT);
            for (Future<RegisterResult> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        }
    }

    /**
     * 시각을 시간대 없는 값으로 두면 DB 에 밀린 값이 들어가는데, 앱 안에서는 읽을 때 거꾸로 되돌아가
     * 응답이 멀쩡하다. 그래서 응답이 아니라 <b>DB 에 적힌 글자</b>를 본다.
     *
     * <p>이 버그는 JVM 시간대가 UTC 가 아닐 때만 드러난다. 그래서 시험 JVM 은 한국 시간대로 돈다(build.gradle).
     */
    @Test
    @DisplayName("DB 에 저장된 시각 자체가 UTC 다 — 정합성 검사가 이 표를 직접 읽는다")
    void storesUtcInDatabase() {
        assertThat(ZoneId.systemDefault().getRules().getOffset(Instant.now()))
                .as("시험 JVM 이 UTC 면 이 시험은 아무것도 증명하지 못한다 — build.gradle 의 user.timezone 확인")
                .isNotEqualTo(ZoneOffset.UTC);

        Registration saved = service.register(UUID.randomUUID().toString(), REQUEST).registration();

        String stored = jdbc.queryForObject(
                "SELECT CAST(confirmed_at AS CHAR) FROM preorder_registrations WHERE external_key = ?",
                String.class, saved.externalKey());
        assertThat(stored).isEqualTo(DB_DATETIME.format(saved.confirmedAt()));
    }

    @Test
    @DisplayName("번호가 겹치면 MySQL 의 1062 를 받아 새 번호로 다시 시도한다")
    void numberCollisionOnMySql() {
        doReturn("R-19990101-0000000001")
                .doReturn("R-19990101-0000000001")
                .doReturn("R-19990101-0000000002")
                .when(numbers).next(any());

        assertThat(service.register(UUID.randomUUID().toString(), REQUEST).registration().externalNumber())
                .isEqualTo("R-19990101-0000000001");
        assertThat(service.register(UUID.randomUUID().toString(), REQUEST).registration().externalNumber())
                .isEqualTo("R-19990101-0000000002");
    }

    @Test
    @DisplayName("중복 키만 재시도 대상으로 알아본다 — CHECK 위반은 Mock 의 버그라 재시도하지 않는다")
    void recognizesOnlyDuplicateKey() {
        String key = UUID.randomUUID().toString();
        repository.saveAndFlush(Registration.cancelMarker(key, Instant.now()));

        assertThatThrownBy(() -> repository.saveAndFlush(Registration.cancelMarker(key, Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(DuplicateKey.isCause(e)).isTrue());

        // ACTIVE 인데 번호가 없다 — ck_registration_active_fields 위반
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO preorder_registrations (external_key, status) VALUES (?, 'ACTIVE')",
                UUID.randomUUID().toString()))
                .isInstanceOf(DataAccessException.class)
                .satisfies(e -> assertThat(DuplicateKey.isCause(e)).isFalse());
    }

    /**
     * 키 조회의 존재 이유. 워커는 응답이 유실되면 이 조회의 404 를 "등록 안 됨" 으로 믿고 재등록한다.
     * 그냥 읽으면 커밋 안 된 등록을 못 본 채 바로 404 가 나가 중복 등록이 된다.
     */
    @Test
    @DisplayName("키 조회는 커밋 전 등록을 기다렸다가 찾는다 - 그 사이 404 를 주지 않는다")
    void byKeyWaitsForUncommittedRegistration() throws Exception {
        String key = UUID.randomUUID().toString();

        MockHttpServletResponse response = lookupWhileRegistrationOpen(key, true);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString())
                .contains(repository.findById(key).orElseThrow().externalNumber());
    }

    /** 기다린 끝의 404 여야 확정 근거다. 롤백된 등록은 없던 일이니 기다린 뒤 404 가 맞다. */
    @Test
    @DisplayName("기다리던 등록이 롤백되면 키 조회는 그 뒤에 404 다")
    void byKeyReturns404AfterRollback() throws Exception {
        String key = UUID.randomUUID().toString();

        MockHttpServletResponse response = lookupWhileRegistrationOpen(key, false);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(repository.findById(key)).isEmpty();
    }

    /**
     * 등록 트랜잭션을 INSERT 직후에 붙잡아 둔 채 키 조회를 보낸다. 조회가 등록이 끝나기 전에 돌아오면
     * 실패다. 그 뒤 등록을 커밋(또는 롤백)하고 조회 응답을 돌려준다.
     */
    private MockHttpServletResponse lookupWhileRegistrationOpen(String key, boolean commit) throws Exception {
        var inserted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                Future<?> registration = pool.submit(() -> tx.executeWithoutResult(status -> {
                    writer.attemptOnce(key, REQUEST);   // 등록과 같은 경로로 INSERT 까지 간다
                    inserted.countDown();
                    awaitQuietly(release);
                    if (!commit) {
                        status.setRollbackOnly();
                    }
                }));
                assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();

                Future<MockHttpServletResponse> lookup = pool.submit(() ->
                        mvc.perform(get("/external/reservations/by-key/{externalKey}", key))
                                .andReturn().getResponse());

                // 잠금 없이 읽으면 여기서 곧바로 404 가 돌아와 있다
                assertThatExceptionOfType(TimeoutException.class)
                        .as("등록이 끝나기 전에 키 조회가 돌아왔다 - 잠금 읽기가 아니다")
                        .isThrownBy(() -> lookup.get(LOCK_WAIT_PROOF_MS, TimeUnit.MILLISECONDS));

                release.countDown();
                registration.get(10, TimeUnit.SECONDS);
                return lookup.get(10, TimeUnit.SECONDS);
            } finally {
                // 단언이 실패해도 등록 스레드를 풀어야 풀이 닫힌다
                release.countDown();
            }
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 키 칸이 utf8mb4_bin 이다. 콜레이션이 대소문자를 무시하면 남의 등록을 내 것으로 알려준다. */
    @Test
    @DisplayName("키 조회는 대소문자를 구별한다")
    void byKeyIsCaseSensitive() throws Exception {
        String key = "Key-" + UUID.randomUUID();
        service.register(key, REQUEST);

        assertThat(mvc.perform(get("/external/reservations/by-key/{externalKey}", key.toUpperCase()))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(get("/external/reservations/by-key/{externalKey}", key))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    /**
     * 명세의 "지연 중 등록 → 취소 → 지연 종료". 지연은 락 밖이라 그 사이 취소가 표식을 남기고, 뒤늦게
     * 진행된 등록은 표식에 막힌다. 표식이 없으면 "취소 성공" 이라고 답해놓고 등록이 살아난다.
     *
     * <p>설정 지연을 크게 잡는 대신 등록을 지연 구간(트랜잭션 밖)에서 붙잡아, 시간에 기대지 않고 이 순서를
     * 정확히 만든다.
     */
    @Test
    @DisplayName("지연 중 등록 → 취소 → 지연 종료 - 취소는 기다리지 않고 성공, 늦게 진행된 등록은 KEY_CANCELED")
    void cancelDuringRegistrationLatency() throws Exception {
        String key = UUID.randomUUID().toString();
        var inLatency = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            inLatency.countDown();
            awaitQuietly(release);
            return invocation.callRealMethod();
        }).when(failureInjector).apply(any());

        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                Future<RegisterResult> registration = pool.submit(() -> service.register(key, REQUEST));
                assertThat(inLatency.await(10, TimeUnit.SECONDS)).isTrue();

                // 등록이 지연 중이어도 취소는 잠금을 기다리지 않는다
                CancelResult canceled = pool.submit(() -> cancellations.cancel(key, null))
                        .get(5, TimeUnit.SECONDS);
                assertThat(canceled.hadActiveRegistration()).isFalse();

                release.countDown();
                assertThatThrownBy(() -> registration.get(10, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .cause()
                        .isInstanceOfSatisfying(MockException.class,
                                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.KEY_CANCELED));
            } finally {
                release.countDown();
            }
        }

        assertThat(repository.findById(key)).get()
                .satisfies(saved -> {
                    assertThat(saved.isCanceled()).isTrue();
                    assertThat(saved.externalNumber()).isNull();
                });
    }

    /**
     * 같은 새 키로 등록과 취소가 동시에 들어오면 READ COMMITTED 에는 갭 락이 없어 둘 다 "없음" 을 보고
     * INSERT 로 간다. 진 쪽은 중복 키를 받아 다시 시도해야 한다. 어떤 순서로 엇갈려도 취소는 모두
     * 성공하고, 끝나면 활성 등록이 없어야 한다.
     */
    @Test
    @DisplayName("같은 새 키로 등록 5 · 취소 5 동시 × 100회 - 취소는 모두 성공, 최종 활성 0, 끈 등록은 있었을 때만 1건")
    void registerAndCancelConcurrently() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            String key = UUID.randomUUID().toString();
            var start = new CountDownLatch(1);
            List<Future<RegisterResult>> registrations = new ArrayList<>();
            List<Future<CancelResult>> cancels = new ArrayList<>();
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < CONCURRENT / 2; i++) {
                    registrations.add(pool.submit(() -> {
                        start.await();
                        return service.register(key, REQUEST);
                    }));
                    cancels.add(pool.submit(() -> {
                        start.await();
                        return cancellations.cancel(key, null);
                    }));
                }
                start.countDown();
            }

            // 등록은 취소보다 먼저 커밋했으면 201(재생 포함), 늦었으면 409 다
            List<String> registeredNumbers = new ArrayList<>();
            for (Future<RegisterResult> registration : registrations) {
                try {
                    registeredNumbers.add(registration.get().registration().externalNumber());
                } catch (ExecutionException e) {
                    assertThat(e.getCause()).as("round %d", round)
                            .isInstanceOfSatisfying(MockException.class,
                                    mock -> assertThat(mock.errorCode()).isEqualTo(ErrorCode.KEY_CANCELED));
                }
            }
            // 취소는 하나도 실패하면 안 된다 — 실패했으면 get() 이 여기서 터진다
            long turnedOff = 0;
            for (Future<CancelResult> cancel : cancels) {
                if (cancel.get().hadActiveRegistration()) {
                    turnedOff++;
                }
            }

            Registration saved = repository.findById(key).orElseThrow();
            assertThat(saved.isCanceled()).as("round %d", round).isTrue();
            if (registeredNumbers.isEmpty()) {
                assertThat(saved.externalNumber()).as("round %d", round).isNull();
                assertThat(turnedOff).as("round %d", round).isZero();
            } else {
                assertThat(registeredNumbers).as("round %d", round).containsOnly(saved.externalNumber());
                assertThat(turnedOff).as("round %d", round).isEqualTo(1);
            }
        }
    }
}
