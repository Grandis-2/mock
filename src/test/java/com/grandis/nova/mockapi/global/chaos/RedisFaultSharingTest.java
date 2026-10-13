package com.grandis.nova.mockapi.global.chaos;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Mock 을 여러 대로 띄웠을 때 결함이 서버끼리 맞는지 진짜 Redis 로 본다.
 *
 * <p>서버 한 대 = 같은 Redis 를 보는 {@link InMemoryFaultStore} 하나. 결함을 건 서버와 그 키의 등록을 받는 서버는
 * 대개 다르다(요청은 서버마다 나뉘어 간다). 메모리면 그때 발동하지 않는다.
 *
 * <p>Docker 가 없으면 이 시험만 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisFaultSharingTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8").withExposedPorts(6379);

    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;

    @BeforeEach
    void setUp() {
        factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        redis.delete(RedisFaultStorage.FAULTS_KEY);
    }

    @AfterEach
    void tearDown() {
        factory.destroy();
    }

    @Test
    @DisplayName("A 서버에 건 응답 유실은 그 키의 등록을 받은 B 서버에서 한 번만 발동한다")
    void faultFiresOnAnotherServerOnce() {
        InMemoryFaultStore a = server();
        InMemoryFaultStore b = server();
        InMemoryFaultStore c = server();

        a.inject("lost-1", FaultType.RESPONSE_LOST_AFTER_COMMIT);

        assertThat(b.consumeResponseLost("lost-1")).isTrue();
        assertThat(c.consumeResponseLost("lost-1")).isFalse();
        assertThat(a.consumeResponseLost("lost-1")).isFalse();
    }

    @Test
    @DisplayName("종류가 다른 결함은 꺼내지 않고 남겨 둔다")
    void otherTypeStays() {
        InMemoryFaultStore a = server();
        InMemoryFaultStore b = server();

        a.inject("slow-1", FaultType.SLOW_SUCCESS, 50);

        assertThat(b.consumeResponseLost("slow-1")).isFalse();
        assertThat(b.pending("slow-1")).isTrue();
    }

    @Test
    @DisplayName("느린 성공의 대기 시간도 다른 서버로 건너가고, 그 서버가 그만큼 기다린다")
    void slowSuccessDelayTravels() {
        InMemoryFaultStore a = server();
        InMemoryFaultStore b = server();
        a.inject("slow-2", FaultType.SLOW_SUCCESS, 80);

        long started = System.nanoTime();
        long waited = b.holdBeforeCommit("slow-2");

        assertThat(waited).isEqualTo(80);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(80));
        assertThat(a.pending("slow-2")).isFalse();
    }

    /**
     * 나눠 꺼내면 같은 키의 동시 등록이 모두 받아 응답이 두 번 유실된다. 그러면 재시도가 저장된 성공에 닿지 못한다.
     *
     * <p>경합은 한 번 통과로 증명되지 않는다(저장소 규칙: 최소 100회). 회차마다 새 키에 걸고 서버 3대에서 동시에 꺼낸다.
     * 공용 풀은 코어가 적은 CI 에서 동시성이 낮아지므로 꺼내는 수만큼 전용 스레드를 둔다.
     */
    @Test
    @DisplayName("여러 서버가 같은 키를 동시에 꺼내도 딱 한 번만 발동한다 (100회)")
    void concurrentTakeFiresOnce() throws Exception {
        List<InMemoryFaultStore> servers = List.of(server(), server(), server());
        int rounds = 100;
        int takers = 30;
        ExecutorService pool = Executors.newFixedThreadPool(takers);
        try {
            for (int round = 0; round < rounds; round++) {
                String key = "race-" + round;
                servers.get(round % servers.size()).inject(key, FaultType.RESPONSE_LOST_AFTER_COMMIT);
                AtomicInteger fired = new AtomicInteger();
                CountDownLatch go = new CountDownLatch(1);

                List<CompletableFuture<Void>> attempts = IntStream.range(0, takers)
                        .mapToObj(i -> CompletableFuture.runAsync(() -> {
                            await(go);
                            if (servers.get(i % servers.size()).consumeResponseLost(key)) {
                                fired.incrementAndGet();
                            }
                        }, pool))
                        .toList();
                go.countDown();
                CompletableFuture.allOf(attempts.toArray(CompletableFuture[]::new)).get();

                assertThat(fired).as("회차 %d", round).hasValue(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("한 서버에서 초기화하면 모든 서버에서 결함이 사라진다")
    void clearReachesEveryServer() {
        InMemoryFaultStore a = server();
        InMemoryFaultStore b = server();
        a.inject("k-1", FaultType.RESPONSE_LOST_AFTER_COMMIT);
        a.inject("k-2", FaultType.SLOW_SUCCESS, 100);

        b.clear();

        assertThat(a.pending("k-1")).isFalse();
        assertThat(a.pending("k-2")).isFalse();
    }

    /** 결함은 시험용 장치다. Redis 장애로 등록 자체가 실패하면 안 된다 — 결함 없음으로 넘어간다. */
    @Test
    @DisplayName("Redis 에 닿지 못하면 꺼내기는 결함 없음으로 넘어가고 예외를 던지지 않는다")
    void unreachableRedisMeansNoFault() throws IOException {
        LettuceConnectionFactory dead = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("localhost", unusedPort()),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(500)).build());
        dead.afterPropertiesSet();
        dead.start();
        try {
            StringRedisTemplate deadRedis = new StringRedisTemplate(dead);
            deadRedis.afterPropertiesSet();
            InMemoryFaultStore store = new InMemoryFaultStore(new RedisFaultStorage(deadRedis));

            assertThat(store.consumeResponseLost("any")).isFalse();
            assertThat(store.holdBeforeCommit("any")).isZero();
        } finally {
            dead.destroy();
        }
    }

    /**
     * 연결은 살아 있는데 Redis 가 답하지 않는 경우(멈춤 · 장애 조치 중). 거부와 달리 바로 실패하지 않고 명령 시간 초과까지
     * 기다린다 — 그래서 {@code spring.data.redis.timeout} 을 짧게 둔다. 정하지 않으면 Lettuce 기본 60초라, 등록마다 결함을
     * 꺼내 보는 redis 모드에서는 모든 등록이 그만큼 멈춘다.
     */
    @Test
    @DisplayName("Redis 가 멈추면 명령 시간 초과(500ms) 안에 결함 없음으로 넘어간다")
    void stalledRedisMeansNoFaultWithinTimeout() {
        LettuceConnectionFactory stalling = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(500)).build());
        stalling.afterPropertiesSet();
        stalling.start();
        try {
            StringRedisTemplate stallingRedis = new StringRedisTemplate(stalling);
            stallingRedis.afterPropertiesSet();
            InMemoryFaultStore store = new InMemoryFaultStore(new RedisFaultStorage(stallingRedis));
            store.inject("stall-1", FaultType.RESPONSE_LOST_AFTER_COMMIT);

            REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
            long started = System.nanoTime();
            boolean fired;
            try {
                fired = store.consumeResponseLost("stall-1");
            } finally {
                REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
            }

            assertThat(fired).isFalse();
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        } finally {
            stalling.destroy();
        }
    }

    /** Mock 서버 한 대. 같은 Redis 를 본다. */
    private InMemoryFaultStore server() {
        return new InMemoryFaultStore(new RedisFaultStorage(redis));
    }

    private static int unusedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
