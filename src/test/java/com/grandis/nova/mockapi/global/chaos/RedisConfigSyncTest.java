package com.grandis.nova.mockapi.global.chaos;

import static org.assertj.core.api.Assertions.assertThat;

import com.grandis.nova.mockapi.global.config.MockProperties;
import com.grandis.nova.mockapi.global.config.SharedState;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/**
 * Mock 을 여러 대로 띄웠을 때 설정이 서버끼리 맞는지 진짜 Redis 로 본다.
 *
 * <p>서버 한 대 = 같은 Redis 를 보는 {@link MockConfigStore} 하나(알림 구독 · 안전망을 각자 갖는다). 2026-10-13 메모리
 * 모드 두 대로 확인한 문제 — 한 대에서 실패율을 30% 로 바꾸면 그 서버만 바뀐다 — 가 여기서는 없어야 한다.
 *
 * <p>Docker 가 없으면 이 시험만 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisConfigSyncTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8").withExposedPorts(6379);

    private static final MockProperties PROPERTIES =
            new MockProperties(500, 0.4, 0.05, FailureMode.HTTP_5XX, 7000, 5000, SharedState.REDIS);

    /** 안전망 주기. 운영은 10초지만 시험은 줄인다. */
    private static final Duration SAFETY = Duration.ofMillis(300);

    /** 알림 · 안전망을 기다리는 한도. 알림은 수 ms, 안전망은 주기 한 번이면 된다. */
    private static final Duration WAIT = Duration.ofSeconds(5);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final List<AutoCloseable> servers = new ArrayList<>();
    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;

    @BeforeEach
    void setUp() {
        factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        redis.delete(RedisConfigSync.CONFIG_KEY);
    }

    @AfterEach
    void tearDown() throws Exception {
        Collections.reverse(servers);
        for (AutoCloseable server : servers) {
            server.close();
        }
        factory.destroy();
    }

    @Test
    @DisplayName("한 대에서 실패율을 바꾸면 다른 서버들도 같은 버전 · 같은 값이 된다")
    void changeReachesOtherServers() {
        MockConfigStore a = server();
        MockConfigStore b = server();
        MockConfigStore c = server();

        MockConfigStore.Applied changed = a.update(500, 0.3, FailureMode.HTTP_5XX);

        assertThat(changed.snapshot().configVersion()).isEqualTo(1);
        for (MockConfigStore other : List.of(b, c)) {
            awaitVersion(other, 1);
            assertThat(other.snapshot().failureRate()).isEqualTo(0.3);
            assertThat(other.applied().appliedAt()).isEqualTo(changed.appliedAt());
        }
    }

    @Test
    @DisplayName("꼬리 · MIXED 섞을 종류까지 그대로 건너간다")
    void wholeConfigTravels() {
        MockConfigStore a = server();
        MockConfigStore b = server();
        LatencyTail tail = new LatencyTail(0.02, 3000, 6000);

        a.update(700, tail, 0.1, FailureMode.MIXED, List.of(MixedResponse.HTTP_502, MixedResponse.HTTP_504));

        awaitVersion(b, 1);
        ConfigSnapshot seen = b.snapshot();
        assertThat(seen.registerLatencyMs()).isEqualTo(700);
        assertThat(seen.latencyTail()).isEqualTo(tail);
        assertThat(seen.failureMode()).isEqualTo(FailureMode.MIXED);
        assertThat(seen.mixedResponses()).containsExactly(MixedResponse.HTTP_502, MixedResponse.HTTP_504);
    }

    /**
     * 한 대가 재기동하면 기본값(5%)이 아니라 다른 서버들과 같은 설정으로 떠야 한다. 기본값으로 뜨면 그 서버만 다른 실패율로
     * 돌고, 처음 뜬 서버의 기본값을 덮어쓰면 다른 서버들까지 바뀐다.
     */
    @Test
    @DisplayName("나중에 뜬 서버(재기동)는 기본값이 아니라 공유된 설정을 따른다")
    void restartedServerFollowsSharedConfig() {
        MockConfigStore first = server();
        assertThat(first.snapshot().configVersion()).isZero();
        assertThat(redis.opsForHash().get(RedisConfigSync.CONFIG_KEY, "version")).isEqualTo("0");

        first.update(500, 0.3, FailureMode.TIMEOUT);
        MockConfigStore restarted = server();

        assertThat(restarted.snapshot().configVersion()).isEqualTo(1);
        assertThat(restarted.snapshot().failureRate()).isEqualTo(0.3);
        assertThat(restarted.snapshot().failureMode()).isEqualTo(FailureMode.TIMEOUT);
    }

    /** 구독이 늦게 붙거나 연결이 잠깐 끊기면 알림을 놓친다. 그래도 안전망 한 주기 안에 맞춰져야 한다. */
    @Test
    @DisplayName("알림을 놓쳐도 안전망이 버전을 보고 따라잡는다")
    void safetyNetCatchesMissedAnnouncement() {
        MockConfigStore a = server();

        // 알림 없이 저장소만 바꾼다 — 다른 서버가 바꾼 뒤 알림이 사라진 것과 같다
        writeConfig(1, "missed", new ConfigSnapshot(500, 0.5, FailureMode.HTTP_5XX, 0));

        awaitVersion(a, 1);
        assertThat(a.snapshot().failureRate()).isEqualTo(0.5);
    }

    /**
     * 버전을 서버마다 올리면 두 서버가 같은 버전에 다른 설정을 갖는다. 공유 저장소가 하나씩 정해야 한다.
     *
     * <p>버전 중복은 Lua 가 막지만, 변경의 기록 · 적용과 알림을 받은 다시 읽기가 끼어드는 순서는 한 번 통과로 증명되지 않는다
     * (저장소 규칙: 최소 100회). 회차마다 그 회차의 시작 버전을 기준으로 마지막 버전을 계산해 두 서버가 맞는지 본다. 공용 풀은
     * 코어가 적은 CI 에서 동시성이 낮아지므로 쓰는 쪽마다 전용 스레드를 둔다.
     */
    @Test
    @DisplayName("두 서버가 동시에 바꿔도 버전이 겹치지 않고, 끝나면 모두 마지막 설정으로 맞는다 (100회)")
    void concurrentChangesGetDistinctVersions() throws Exception {
        MockConfigStore a = server();
        MockConfigStore b = server();
        int perServer = 20;
        int rounds = 100;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < rounds; round++) {
                int base = a.snapshot().configVersion();
                ConcurrentLinkedQueue<Integer> versions = new ConcurrentLinkedQueue<>();
                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch go = new CountDownLatch(1);

                List<CompletableFuture<Void>> writers = List.of(a, b).stream()
                        .map(store -> CompletableFuture.runAsync(() -> {
                            ready.countDown();
                            await(go);
                            IntStream.range(0, perServer).forEach(i ->
                                    versions.add(store.update(500, i / 100.0, FailureMode.HTTP_5XX)
                                            .snapshot().configVersion()));
                        }, pool))
                        .toList();
                // 둘 다 출발선에 선 뒤에 놓는다. 바로 놓으면 한쪽이 20번을 다 끝낸 뒤에 다른 쪽이 시작할 수 있다
                await(ready);
                go.countDown();
                CompletableFuture.allOf(writers.toArray(CompletableFuture[]::new)).get();

                assertThat(versions).as("회차 %d", round).doesNotHaveDuplicates().hasSize(perServer * 2);
                int last = base + perServer * 2;
                awaitVersion(a, last);
                awaitVersion(b, last);
                assertThat(a.snapshot()).as("회차 %d", round).isEqualTo(b.snapshot());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Redis 가 재시작해 설정을 잃으면 저장된 버전이 0 부터 다시 시작한다. 그대로 1 을 주면 버전 2 를 아는 다른 서버들이 새
     * 설정을 옛것으로 보고 버려 서버마다 설정이 갈린다.
     */
    @Test
    @DisplayName("Redis 가 설정을 잃은 뒤에 바꿔도 버전이 거꾸로 가지 않고 다른 서버가 받는다")
    void versionDoesNotRegressAfterRedisLosesConfig() {
        MockConfigStore a = server();
        MockConfigStore b = server();
        a.update(500, 0.3, FailureMode.HTTP_5XX);
        a.update(500, 0.4, FailureMode.HTTP_5XX);
        awaitVersion(b, 2);

        redis.delete(RedisConfigSync.CONFIG_KEY);
        MockConfigStore.Applied changed = b.update(500, 0.1, FailureMode.HTTP_5XX);

        assertThat(changed.snapshot().configVersion()).isEqualTo(3);
        awaitVersion(a, 3);
        assertThat(a.snapshot().failureRate()).isEqualTo(0.1);
    }

    @Test
    @DisplayName("Redis 가 설정을 잃으면 안전망이 마지막 설정을 그 버전 그대로 다시 올린다")
    void safetyNetRestoresLostConfig() {
        MockConfigStore a = server();
        a.update(500, 0.3, FailureMode.TIMEOUT);

        redis.delete(RedisConfigSync.CONFIG_KEY);

        awaitTrue(() -> "1".equals(redis.opsForHash().get(RedisConfigSync.CONFIG_KEY, "version")),
                "안전망이 설정을 다시 올리지 않았다");
        MockConfigStore later = server();
        assertThat(later.snapshot().configVersion()).isEqualTo(1);
        assertThat(later.snapshot().failureRate()).isEqualTo(0.3);
        assertThat(later.snapshot().failureMode()).isEqualTo(FailureMode.TIMEOUT);
    }

    /**
     * Redis 가 설정을 잃은 뒤 옛 버전을 아는 서버가 먼저 다시 올리면 Redis 에 옛 설정이 남는다. 더 새 버전을 아는 서버의
     * 안전망이 그것을 바로잡아야 한다 — 안 그러면 나중에 뜨는 서버가 옛 설정을 받는다.
     */
    @Test
    @DisplayName("옛 버전이 먼저 다시 올라가도 더 새 버전을 아는 서버가 바로잡는다")
    void newerServerCorrectsStaleRestore() {
        MockConfigStore a = server();
        a.update(500, 0.3, FailureMode.HTTP_5XX);
        a.update(500, 0.4, FailureMode.TIMEOUT);

        // 옛 버전(1)을 아는 서버가 먼저 다시 올린 것과 같다
        redis.delete(RedisConfigSync.CONFIG_KEY);
        writeConfig(1, "stale", new ConfigSnapshot(500, 0.3, FailureMode.HTTP_5XX, 0));

        awaitTrue(() -> "2".equals(redis.opsForHash().get(RedisConfigSync.CONFIG_KEY, "version")),
                "더 새 버전(2)으로 바로잡지 않았다");
        MockConfigStore later = server();
        assertThat(later.snapshot().configVersion()).isEqualTo(2);
        assertThat(later.snapshot().failureRate()).isEqualTo(0.4);
        assertThat(later.snapshot().failureMode()).isEqualTo(FailureMode.TIMEOUT);
    }

    /**
     * Redis 가 설정을 잃은 틈에 늦어 있던 서버가 바꾸면, 다른 서버가 아는 것과 같은 버전 번호에 다른 설정이 올라간다. 버전만
     * 보면 그 서버는 옛 내용을 계속 쓴다. 변경 ID 가 다르면 Redis 를 따라야 한다.
     */
    @Test
    @DisplayName("같은 버전인데 다른 변경이면 모든 서버가 Redis 의 것을 따른다")
    void sameVersionDifferentChangeFollowsRedis() {
        MockConfigStore a = server();
        MockConfigStore b = server();
        a.update(500, 0.3, FailureMode.HTTP_5XX);
        awaitVersion(b, 1);

        // 설정을 잃은 Redis 에 다른 서버가 "버전 1" 로 다른 설정을 올린 것과 같다
        redis.delete(RedisConfigSync.CONFIG_KEY);
        writeConfig(1, "other-change", new ConfigSnapshot(800, 0.7, FailureMode.TIMEOUT, 0));

        for (MockConfigStore store : List.of(a, b)) {
            awaitTrue(() -> store.snapshot().failureRate() == 0.7,
                    "Redis 의 같은 버전 · 다른 설정을 따르지 않았다: " + store.snapshot().failureRate());
            assertThat(store.snapshot().configVersion()).isEqualTo(1);
            assertThat(store.snapshot().failureMode()).isEqualTo(FailureMode.TIMEOUT);
            assertThat(store.snapshot().registerLatencyMs()).isEqualTo(800);
        }
    }

    /** 알림 순서가 뒤바뀌어 옛 버전이 늦게 와도 설정을 되돌리지 않는다. */
    @Test
    @DisplayName("늦게 온 옛 버전은 받지 않는다")
    void olderVersionDoesNotRollBack() {
        MockConfigStore a = server();
        a.update(500, 0.3, FailureMode.HTTP_5XX);
        a.update(500, 0.4, FailureMode.HTTP_5XX);

        a.accept(new MockConfigStore.Applied(new ConfigSnapshot(500, 0.3, FailureMode.HTTP_5XX, 1), Instant.now()));

        assertThat(a.snapshot().configVersion()).isEqualTo(2);
        assertThat(a.snapshot().failureRate()).isEqualTo(0.4);
    }

    /** Mock 서버 한 대. 같은 Redis 를 보되 알림 구독 · 안전망은 각자 갖는다. */
    private MockConfigStore server() {
        RedisMessageListenerContainer listeners = new RedisMessageListenerContainer();
        listeners.setConnectionFactory(factory);
        listeners.afterPropertiesSet();
        listeners.start();
        RedisConfigSync sync = new RedisConfigSync(redis, listeners, JSON, SAFETY);
        servers.add(() -> {
            sync.destroy();
            listeners.stop();
            listeners.destroy();
        });
        return new MockConfigStore(PROPERTIES, sync);
    }

    /**
     * 다른 서버가 쓴 것처럼 설정을 한 번에 쓴다(HSET 한 명령). 칸을 따로 쓰면 그 사이에 안전망이 끼어 버전과 내용이 다른
     * 설정에서 온 것으로 섞인다.
     */
    private void writeConfig(int version, String changeId, ConfigSnapshot snapshot) {
        redis.opsForHash().putAll(RedisConfigSync.CONFIG_KEY, Map.of(
                "version", String.valueOf(version),
                "changeId", changeId,
                "body", JSON.writeValueAsString(RedisConfigSync.Body.of(snapshot)),
                "appliedAt", Instant.now().toString()));
    }

    private static void awaitVersion(MockConfigStore store, int version) {
        awaitTrue(() -> store.snapshot().configVersion() == version,
                "configVersion " + version + " 을 기다렸지만 " + store.snapshot().configVersion());
    }

    private static void awaitTrue(Supplier<Boolean> condition, String message) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.get()) {
                return;
            }
            sleep(20);
        }
        throw new AssertionError(message);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
