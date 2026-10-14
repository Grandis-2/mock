package com.grandis.nova.mockapi.draw.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.grandis.nova.mockapi.global.error.ErrorCode;
import com.grandis.nova.mockapi.global.error.MockException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * 럭키드로우 응모의 동시성. 진짜 MySQL 로 본다.
 *
 * <p>응모는 회차 행 잠금과 중복 키(1062)에 기댄다. H2 는 둘 다 다르게 동작하므로 여기서 통과해야 의미가 있다. 스키마는
 * {@code docs/schema.sql} 을 그대로 넣는다({@code ddl-auto=validate} 로 엔티티와 맞는지도 확인된다).
 *
 * <p><b>서버의 격리 수준은 MySQL 기본값(REPEATABLE READ) 그대로 둔다</b> — 등록 시험({@code RegistrationConcurrencyTest})과
 * 같은 이유다. READ COMMITTED 는 코드(트랜잭션 경계)가 정하고, 빠지면 처음 보는 회차 둘의 INSERT 가 갭 락으로 교착한다.
 *
 * <p>경합은 한 번 통과로 증명되지 않는다(저장소 규칙: 최소 100회). Docker 가 없으면 이 시험만 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.datasource.hikari.maximum-pool-size=20"
})
class DrawEntryConcurrencyTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("external_mock")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("docs/schema.sql"), "/docker-entrypoint-initdb.d/01-schema.sql")
            .withCommand(
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci");

    private static final int ROUNDS = 100;
    private static final int CONCURRENT = 10;

    /** 서로 다른 처음 보는 회차 동시 응모. 격리 수준이 빠지면 이 크기에서 교착이 난다(등록 시험과 같은 규모). */
    private static final int DISTINCT_DRAWS = 50;
    private static final int DISTINCT_ROUNDS = 5;

    @Autowired
    private DrawEntryService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("시험 서버의 커넥션은 MySQL 기본값 REPEATABLE READ 그대로다")
    void serverDefaultIsRepeatableRead() {
        assertThat(jdbc.queryForObject("SELECT @@transaction_isolation", String.class))
                .isEqualTo("REPEATABLE-READ");
    }

    @Test
    @DisplayName("처음 보는 회차에 다른 회원 10명 동시 × 100회 - 회차는 하나, 응모는 10건 모두 새로")
    void newDrawConcurrently() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            String drawId = UUID.randomUUID().toString();
            List<Callable<DrawEntryResult>> calls = new ArrayList<>(CONCURRENT);
            for (int i = 0; i < CONCURRENT; i++) {
                String entryId = UUID.randomUUID().toString();
                DrawEntryCommand command = new DrawEntryCommand(drawId, UUID.randomUUID());
                calls.add(() -> service.enter(entryId, command));
            }

            List<Outcome> outcomes = runConcurrently(calls);

            assertThat(outcomes).as("round %d", round).allSatisfy(outcome -> {
                assertThat(outcome.error()).isNull();
                assertThat(outcome.result().replayed()).isFalse();
            });
            assertThat(countDraws(drawId)).as("round %d", round).isEqualTo(1);
            assertThat(countEntries(drawId)).as("round %d", round).isEqualTo(CONCURRENT);
        }
    }

    @Test
    @DisplayName("처음 보는 회차에 같은 응모 10건 동시 × 100회 - 새 응모 1건, 나머지 9건은 재생")
    void sameEntryConcurrently() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            String entryId = UUID.randomUUID().toString();
            String drawId = UUID.randomUUID().toString();
            DrawEntryCommand command = new DrawEntryCommand(drawId, UUID.randomUUID());
            List<Callable<DrawEntryResult>> calls = new ArrayList<>(CONCURRENT);
            for (int i = 0; i < CONCURRENT; i++) {
                calls.add(() -> service.enter(entryId, command));
            }

            List<Outcome> outcomes = runConcurrently(calls);

            assertThat(outcomes).as("round %d", round).allSatisfy(outcome -> assertThat(outcome.error()).isNull());
            assertThat(outcomes).as("round %d", round)
                    .filteredOn(outcome -> !outcome.result().replayed()).hasSize(1);
            assertThat(countEntries(drawId)).as("round %d", round).isEqualTo(1);
        }
    }

    /** be 버그(회차당 회원 하나를 어김)가 동시에 와도 한 회원이 두 번 추첨에 들어가지 않는다. */
    @Test
    @DisplayName("같은 회차에 같은 회원의 다른 응모 10건 동시 × 100회 - 1건만 받고 9건은 409")
    void sameCustomerConcurrently() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            String drawId = UUID.randomUUID().toString();
            UUID customerId = UUID.randomUUID();
            List<Callable<DrawEntryResult>> calls = new ArrayList<>(CONCURRENT);
            for (int i = 0; i < CONCURRENT; i++) {
                String entryId = UUID.randomUUID().toString();
                calls.add(() -> service.enter(entryId, new DrawEntryCommand(drawId, customerId)));
            }

            List<Outcome> outcomes = runConcurrently(calls);

            assertThat(outcomes).as("round %d", round).filteredOn(outcome -> outcome.error() == null).hasSize(1);
            assertThat(outcomes).as("round %d", round).filteredOn(outcome -> outcome.error() != null)
                    .allSatisfy(outcome -> assertThat(outcome.error())
                            .isInstanceOfSatisfying(MockException.class, e ->
                                    assertThat(e.errorCode()).isEqualTo(ErrorCode.CUSTOMER_ALREADY_ENTERED)));
            assertThat(countEntries(drawId)).as("round %d", round).isEqualTo(1);
        }
    }

    /**
     * 서로 다른 처음 보는 회차를 동시에 만들면, 격리 수준이 REPEATABLE READ 일 때 없는 행을 잠그는 읽기가 갭 락을 걸어
     * INSERT 끼리 교착(1213)한다. Writer 가 READ COMMITTED 를 정해 두었는지 여기서 드러난다.
     */
    @Test
    @DisplayName("서로 다른 처음 보는 회차 동시 50건 × 5회 - 서버가 REPEATABLE READ 여도 모두 들어간다")
    void distinctNewDrawsConcurrently() throws Exception {
        for (int round = 0; round < DISTINCT_ROUNDS; round++) {
            List<Callable<DrawEntryResult>> calls = new ArrayList<>(DISTINCT_DRAWS);
            for (int i = 0; i < DISTINCT_DRAWS; i++) {
                String entryId = UUID.randomUUID().toString();
                DrawEntryCommand command = new DrawEntryCommand(UUID.randomUUID().toString(), UUID.randomUUID());
                calls.add(() -> service.enter(entryId, command));
            }

            List<Outcome> outcomes = runConcurrently(calls);

            assertThat(outcomes).as("round %d", round).allSatisfy(outcome -> {
                assertThat(outcome.error()).isNull();
                assertThat(outcome.result().replayed()).isFalse();
            });
        }
    }

    /** 시험이 기대한 거절(409)도 결과로 모은다. 그 밖의 예외는 그대로 남겨 단정에서 드러나게 한다. */
    private record Outcome(DrawEntryResult result, Throwable error) {
    }

    private static List<Outcome> runConcurrently(List<Callable<DrawEntryResult>> calls) throws Exception {
        var start = new CountDownLatch(1);
        List<Future<DrawEntryResult>> futures = new ArrayList<>(calls.size());
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Callable<DrawEntryResult> call : calls) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<Outcome> outcomes = new ArrayList<>(calls.size());
            for (Future<DrawEntryResult> future : futures) {
                try {
                    outcomes.add(new Outcome(future.get(30, TimeUnit.SECONDS), null));
                } catch (ExecutionException e) {
                    outcomes.add(new Outcome(null, e.getCause()));
                }
            }
            return outcomes;
        }
    }

    private int countDraws(String drawId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM draws WHERE draw_id = ?", Integer.class, drawId);
    }

    private int countEntries(String drawId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM draw_entries WHERE draw_id = ?", Integer.class, drawId);
    }
}
