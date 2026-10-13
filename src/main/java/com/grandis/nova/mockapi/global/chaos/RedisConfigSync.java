package com.grandis.nova.mockapi.global.chaos;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Redis 로 설정을 나눈다. 여러 대로 띄울 때 쓴다({@code mock.shared-state=redis}).
 *
 * <p>저장은 해시 {@value #CONFIG_KEY} 한 개({@code version} · {@code body} · {@code appliedAt}). 바꾼 서버는
 * {@value #CHANNEL} 채널로 새 버전을 알리고, 다른 서버들은 받아서 다시 읽는다. 요청 처리는 지금처럼 각 서버의 메모리
 * 값을 쓰므로 요청마다 Redis 를 보지 않는다.
 *
 * <p><b>알림을 놓칠 수 있다.</b> 구독은 기동 뒤 조금 늦게 붙고, 연결이 끊긴 사이의 알림은 다시 오지 않는다. 그래서
 * {@link #SAFETY_INTERVAL} 마다 버전만 읽어 맞춘다.
 *
 * <p>기동 · 변경은 Lua 스크립트로 한 번에 한다. 두 서버가 동시에 처음 뜨면 둘 다 기본값을 쓰고, 동시에 바꾸면 같은
 * 버전에 다른 설정이 남는다 — 스크립트는 Redis 안에서 한 번에 돌아 그 틈이 없다.
 */
@Component
@ConditionalOnProperty(prefix = "mock", name = "shared-state", havingValue = "redis")
public class RedisConfigSync implements ConfigSync, DisposableBean {

    static final String CONFIG_KEY = "mock:config";
    static final String CHANNEL = "mock:config:changed";
    static final Duration SAFETY_INTERVAL = Duration.ofSeconds(10);

    private static final Logger log = LoggerFactory.getLogger(RedisConfigSync.class);

    /** 없을 때만 기본값을 넣고, 저장된 값을 돌려준다. */
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SEED = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then
              redis.call('HSET', KEYS[1], 'version', '0', 'body', ARGV[1], 'appliedAt', ARGV[2])
            end
            return redis.call('HMGET', KEYS[1], 'version', 'body', 'appliedAt')
            """, List.class);

    /**
     * 버전을 하나 올려 저장하고 알린다. 정해진 버전을 돌려준다.
     *
     * <p>버전은 "저장된 버전과 이 서버가 아는 버전 중 큰 것 + 1" 이다. Redis 가 재시작해 설정을 잃으면 저장된 버전이
     * 0 부터 다시 시작하는데, 그대로 올리면 다른 서버들이 아는 버전보다 작아 새 설정을 옛것으로 보고 버린다.
     */
    private static final RedisScript<Long> PUBLISH = new DefaultRedisScript<>("""
            local stored = tonumber(redis.call('HGET', KEYS[1], 'version') or '0')
            local version = math.max(stored, tonumber(ARGV[4])) + 1
            redis.call('HSET', KEYS[1], 'version', tostring(version), 'body', ARGV[1], 'appliedAt', ARGV[2])
            redis.call('PUBLISH', ARGV[3], tostring(version))
            return version
            """, Long.class);

    /**
     * 없거나 저장된 버전이 더 낮을 때만 주어진 버전 그대로 넣는다. Redis 가 설정을 잃었을 때 안전망이 쓴다. 알리지 않는다 —
     * 다른 서버들은 이미 그 버전을 알고 있다.
     *
     * <p>"없을 때만" 이면 옛 버전을 아는 서버가 먼저 올렸을 때 더 새 버전을 아는 서버가 바로잡지 못해, 나중에 뜨는 서버가 옛
     * 설정을 받는다.
     */
    private static final RedisScript<Long> RESTORE = new DefaultRedisScript<>("""
            local stored = redis.call('HGET', KEYS[1], 'version')
            if (not stored) or tonumber(stored) < tonumber(ARGV[1]) then
              redis.call('HSET', KEYS[1], 'version', ARGV[1], 'body', ARGV[2], 'appliedAt', ARGV[3])
              return 1
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final RedisMessageListenerContainer listeners;
    private final JsonMapper json;
    private final Duration safetyInterval;
    private final ScheduledExecutorService safetyCheck =
            Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("mock-config-sync").daemon().factory());

    /** 마지막으로 반영한 버전. 알림 · 안전망이 같은 버전을 두 번 읽지 않게 한다. */
    private volatile int knownVersion = -1;

    /** 마지막으로 반영한 설정. Redis 가 설정을 잃었을 때 다시 올린다. */
    private volatile MockConfigStore.Applied knownConfig;

    @Autowired
    public RedisConfigSync(StringRedisTemplate redis, RedisMessageListenerContainer listeners, JsonMapper json) {
        this(redis, listeners, json, SAFETY_INTERVAL);
    }

    /** 시험이 안전망 주기를 줄여 쓴다. */
    RedisConfigSync(StringRedisTemplate redis, RedisMessageListenerContainer listeners, JsonMapper json,
                    Duration safetyInterval) {
        this.redis = redis;
        this.listeners = listeners;
        this.json = json;
        this.safetyInterval = safetyInterval;
    }

    @Override
    public MockConfigStore.Applied start(MockConfigStore.Applied defaults, Consumer<MockConfigStore.Applied> onChange) {
        List<?> stored = redis.execute(SEED, List.of(CONFIG_KEY),
                write(defaults.snapshot()), defaults.appliedAt().toString());
        MockConfigStore.Applied current = read(stored);
        remember(current);

        listeners.addMessageListener((message, pattern) -> {
            int announced = Integer.parseInt(new String(message.getBody(), StandardCharsets.UTF_8));
            if (announced > knownVersion) {
                reload(onChange);
            }
        }, new ChannelTopic(CHANNEL));

        long period = safetyInterval.toMillis();
        safetyCheck.scheduleWithFixedDelay(() -> catchUp(onChange), period, period, TimeUnit.MILLISECONDS);
        return current;
    }

    @Override
    public MockConfigStore.Applied publish(ConfigSnapshot next, Instant appliedAt) {
        Long version = redis.execute(PUBLISH, List.of(CONFIG_KEY), write(next), appliedAt.toString(), CHANNEL,
                String.valueOf(Math.max(knownVersion, 0)));
        MockConfigStore.Applied stored = new MockConfigStore.Applied(withVersion(next, version.intValue()), appliedAt);
        remember(stored);
        return stored;
    }

    /**
     * 안전망. 버전만 읽어 보고 늦었으면 다시 읽는다. Redis 가 잠깐 안 되면 다음 차례에 다시 본다.
     *
     * <p>설정이 없거나 이 서버가 아는 것보다 옛 버전이면 Redis 가 재시작해 잃었거나, 옛 버전을 아는 서버가 먼저 다시 올린
     * 것이다. 이 서버가 아는 마지막 설정을 그 버전 그대로 다시 올린다 — 다른 서버들도 같은 버전을 알고 있으므로 아무것도
     * 바뀌지 않고, 다음 변경은 그 위에서 이어진다. 여러 서버가 동시에 올려도 "없거나 더 낮을 때만" 이라 가장 새 버전이 남는다.
     */
    private void catchUp(Consumer<MockConfigStore.Applied> onChange) {
        try {
            Object version = redis.opsForHash().get(CONFIG_KEY, "version");
            if (version == null || Integer.parseInt(version.toString()) < knownVersion) {
                restore();
            } else if (Integer.parseInt(version.toString()) > knownVersion) {
                reload(onChange);
            }
        } catch (RuntimeException e) {
            log.warn("공유 설정 확인 실패 — 다음 차례에 다시 본다: {}", e.toString());
        }
    }

    private void restore() {
        MockConfigStore.Applied last = knownConfig;
        if (last == null) {
            return;
        }
        redis.execute(RESTORE, List.of(CONFIG_KEY), String.valueOf(last.snapshot().configVersion()),
                write(last.snapshot()), last.appliedAt().toString());
        log.warn("공유 설정이 Redis 에 없거나 옛것이라 마지막 설정을 다시 올렸다 configVersion={}",
                last.snapshot().configVersion());
    }

    /**
     * 비교와 기록을 한 번에 한다. 변경(요청 스레드)과 다시 읽기(알림 · 안전망)가 따로 비교하고 따로 쓰면, 5 를 쓴 뒤 4 가
     * 덮어 아는 버전이 내려간다. {@link #reload} 와 같은 잠금이다.
     */
    private synchronized void remember(MockConfigStore.Applied applied) {
        if (applied.snapshot().configVersion() >= knownVersion) {
            knownVersion = applied.snapshot().configVersion();
            knownConfig = applied;
        }
    }

    private synchronized void reload(Consumer<MockConfigStore.Applied> onChange) {
        List<Object> stored = redis.opsForHash().multiGet(CONFIG_KEY, List.of("version", "body", "appliedAt"));
        MockConfigStore.Applied applied = read(stored);
        int version = applied.snapshot().configVersion();
        if (version <= knownVersion) {
            return;
        }
        remember(applied);
        onChange.accept(applied);
        log.info("다른 서버가 바꾼 설정을 반영한다 configVersion={} failureRate={} failureMode={}",
                version, applied.snapshot().failureRate(), applied.snapshot().failureMode());
    }

    private MockConfigStore.Applied read(List<?> stored) {
        int version = Integer.parseInt(String.valueOf(stored.get(0)));
        Body body = json.readValue(String.valueOf(stored.get(1)), Body.class);
        Instant appliedAt = Instant.parse(String.valueOf(stored.get(2)));
        return new MockConfigStore.Applied(body.toSnapshot(version), appliedAt);
    }

    private String write(ConfigSnapshot snapshot) {
        return json.writeValueAsString(Body.of(snapshot));
    }

    private static ConfigSnapshot withVersion(ConfigSnapshot s, int version) {
        return new ConfigSnapshot(s.registerLatencyMs(), s.failureRate(), s.failureMode(), version,
                s.latencyTail(), s.mixedResponses());
    }

    @Override
    public void destroy() {
        safetyCheck.shutdownNow();
    }

    /**
     * Redis 에 두는 모양. 버전은 따로 둔다(스크립트가 올리므로). 열거형은 이름으로, 꼬리는 칸 셋으로 펴서 둔다 —
     * 다른 서버가 같은 클래스로 읽으므로 모양이 바뀌면 함께 바뀐다.
     */
    record Body(int registerLatencyMs, double failureRate, String failureMode,
                double tailRate, int tailMinMs, int tailMaxMs, List<String> mixedResponses) {

        static Body of(ConfigSnapshot s) {
            return new Body(s.registerLatencyMs(), s.failureRate(), s.failureMode().name(),
                    s.latencyTail().rate(), s.latencyTail().minMs(), s.latencyTail().maxMs(),
                    s.mixedResponses().stream().map(Enum::name).toList());
        }

        ConfigSnapshot toSnapshot(int version) {
            return new ConfigSnapshot(registerLatencyMs, failureRate, FailureMode.valueOf(failureMode), version,
                    new LatencyTail(tailRate, tailMinMs, tailMaxMs),
                    mixedResponses.stream().map(MixedResponse::valueOf).toList());
        }
    }
}
