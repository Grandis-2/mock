package com.grandis.nova.mockapi.global.chaos;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
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
 * <p>저장은 해시 {@value #CONFIG_KEY} 한 개({@code version} · {@code changeId} · {@code body} · {@code appliedAt}). 바꾼
 * 서버는 {@value #CHANNEL} 채널로 {@code 버전:변경ID} 를 알리고, 다른 서버들은 받아서 다시 읽는다. 요청 처리는 지금처럼 각
 * 서버의 메모리 값을 쓰므로 요청마다 Redis 를 보지 않는다.
 *
 * <p><b>버전과 변경 ID 를 같이 본다.</b> Redis 가 설정을 잃은 틈에 한 서버가 바꾸면, 서로 다른 설정이 같은 버전 번호를
 * 가질 수 있다(늦어 있던 서버가 낮은 버전 위에서 올린 경우). 버전만 보면 같은 번호를 아는 서버가 옛 내용을 계속 쓴다.
 * 그래서 버전이 같아도 변경 ID 가 다르면 <b>Redis 를 따른다</b> — Redis 에 있는 것이 기준이다.
 *
 * <p><b>알림을 놓칠 수 있다.</b> 구독은 기동 뒤 조금 늦게 붙고, 연결이 끊긴 사이의 알림은 다시 오지 않는다. 그래서
 * {@link #SAFETY_INTERVAL} 마다 버전 · 변경 ID 만 읽어 맞춘다.
 *
 * <p>기동 · 변경 · 복구는 Lua 스크립트로 한 번에 한다. 두 서버가 동시에 처음 뜨면 둘 다 기본값을 쓰고, 동시에 바꾸면 같은
 * 버전에 다른 설정이 남는다 — 스크립트는 Redis 안에서 한 번에 돌아 그 틈이 없다.
 */
@Component
@ConditionalOnProperty(prefix = "mock", name = "shared-state", havingValue = "redis")
public class RedisConfigSync implements ConfigSync, DisposableBean {

    static final String CONFIG_KEY = "mock:config";
    static final String CHANNEL = "mock:config:changed";
    static final Duration SAFETY_INTERVAL = Duration.ofSeconds(10);

    private static final Logger log = LoggerFactory.getLogger(RedisConfigSync.class);
    private static final List<Object> FIELDS = List.of("version", "changeId", "body", "appliedAt");

    /** 없을 때만 기본값을 넣고, 저장된 값을 돌려준다. */
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SEED = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then
              redis.call('HSET', KEYS[1], 'version', '0', 'changeId', ARGV[1], 'body', ARGV[2], 'appliedAt', ARGV[3])
            end
            return redis.call('HMGET', KEYS[1], 'version', 'changeId', 'body', 'appliedAt')
            """, List.class);

    /**
     * 버전을 하나 올려 새 변경 ID 와 함께 저장하고 알린다. 정해진 버전을 돌려준다.
     *
     * <p>버전은 "저장된 버전과 이 서버가 아는 버전 중 큰 것 + 1" 이다. Redis 가 재시작해 설정을 잃으면 저장된 버전이
     * 0 부터 다시 시작하는데, 그대로 올리면 다른 서버들이 아는 버전보다 작아 새 설정을 옛것으로 보고 버린다.
     */
    private static final RedisScript<Long> PUBLISH = new DefaultRedisScript<>("""
            local stored = tonumber(redis.call('HGET', KEYS[1], 'version') or '0')
            local version = math.max(stored, tonumber(ARGV[1])) + 1
            redis.call('HSET', KEYS[1], 'version', tostring(version), 'changeId', ARGV[2],
                       'body', ARGV[3], 'appliedAt', ARGV[4])
            redis.call('PUBLISH', ARGV[5], tostring(version) .. ':' .. ARGV[2])
            return version
            """, Long.class);

    /**
     * 없거나 저장된 버전이 더 낮을 때만 주어진 버전 · 변경 ID 그대로 넣는다. Redis 가 설정을 잃었을 때 안전망이 쓴다.
     * 알리지 않는다 — 다른 서버들은 이미 그 버전을 알고 있다.
     *
     * <p>"없을 때만" 이면 옛 버전을 아는 서버가 먼저 올렸을 때 더 새 버전을 아는 서버가 바로잡지 못해, 나중에 뜨는 서버가 옛
     * 설정을 받는다.
     */
    private static final RedisScript<Long> RESTORE = new DefaultRedisScript<>("""
            local stored = redis.call('HGET', KEYS[1], 'version')
            if (not stored) or tonumber(stored) < tonumber(ARGV[1]) then
              redis.call('HSET', KEYS[1], 'version', ARGV[1], 'changeId', ARGV[2], 'body', ARGV[3], 'appliedAt', ARGV[4])
              return 1
            end
            return 0
            """, Long.class);

    /** 반영한 설정의 표식. 같은 버전이어도 변경 ID 가 다르면 다른 설정이다. */
    private record Stamp(int version, String changeId) {

        static final Stamp NONE = new Stamp(-1, "");

        /** Redis 에 있는 것({@code stored})을 따라야 하는가. 더 새 버전이거나, 같은 버전인데 다른 변경이면 따른다. */
        boolean behind(Stamp stored) {
            return stored.version > version || (stored.version == version && !stored.changeId.equals(changeId));
        }
    }

    /** 반영한 설정과 그 표식. 한 묶음으로 바꿔 둘이 어긋나지 않게 한다. */
    private record Known(Stamp stamp, MockConfigStore.Applied applied) {
    }

    private final StringRedisTemplate redis;
    private final RedisMessageListenerContainer listeners;
    private final JsonMapper json;
    private final Duration safetyInterval;
    private final StartupRetry startupRetry;
    private final ScheduledExecutorService safetyCheck =
            Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("mock-config-sync").daemon().factory());

    /** 마지막으로 반영한 설정. 알림 · 안전망이 같은 설정을 두 번 읽지 않게 하고, Redis 가 잃으면 다시 올린다. */
    private volatile Known known = new Known(Stamp.NONE, null);

    @Autowired
    public RedisConfigSync(StringRedisTemplate redis, RedisMessageListenerContainer listeners, JsonMapper json) {
        this(redis, listeners, json, SAFETY_INTERVAL);
    }

    /** 시험이 안전망 주기를 줄여 쓴다. */
    RedisConfigSync(StringRedisTemplate redis, RedisMessageListenerContainer listeners, JsonMapper json,
                    Duration safetyInterval) {
        this(redis, listeners, json, safetyInterval, StartupRetry.DEFAULT);
    }

    /** 시험이 기동 재시도 상한을 줄여 쓴다. */
    RedisConfigSync(StringRedisTemplate redis, RedisMessageListenerContainer listeners, JsonMapper json,
                    Duration safetyInterval, StartupRetry startupRetry) {
        this.redis = redis;
        this.listeners = listeners;
        this.json = json;
        this.safetyInterval = safetyInterval;
        this.startupRetry = startupRetry;
    }

    @Override
    public MockConfigStore.Applied start(MockConfigStore.Applied defaults, Consumer<MockConfigStore.Applied> onChange) {
        // 기동 중 첫 Redis 명령이라 첫 연결도 여기서 맺는다. 막 뜬 JVM 은 첫 연결이 시간 초과를 넘기기 쉬워 다시 시도한다.
        String changeId = newChangeId();
        String body = write(defaults.snapshot());
        List<?> stored = startupRetry.run("공유 설정 첫 읽기(Redis 첫 연결)", () -> redis.execute(SEED,
                List.of(CONFIG_KEY), changeId, body, defaults.appliedAt().toString()));
        Known current = read(stored);
        remember(current);

        listeners.addMessageListener((message, pattern) -> {
            Stamp announced = parseAnnouncement(new String(message.getBody(), StandardCharsets.UTF_8));
            if (known.stamp().behind(announced)) {
                reload(onChange);
            }
        }, new ChannelTopic(CHANNEL));

        long period = safetyInterval.toMillis();
        safetyCheck.scheduleWithFixedDelay(() -> catchUp(onChange), period, period, TimeUnit.MILLISECONDS);
        return current.applied();
    }

    @Override
    public MockConfigStore.Applied publish(ConfigSnapshot next, Instant appliedAt) {
        String changeId = newChangeId();
        Long version = redis.execute(PUBLISH, List.of(CONFIG_KEY),
                String.valueOf(Math.max(known.stamp().version(), 0)), changeId, write(next), appliedAt.toString(),
                CHANNEL);
        MockConfigStore.Applied stored = new MockConfigStore.Applied(withVersion(next, version.intValue()), appliedAt);
        remember(new Known(new Stamp(version.intValue(), changeId), stored));
        return stored;
    }

    /**
     * 안전망. 버전 · 변경 ID 만 읽어 보고 맞춘다. Redis 가 잠깐 안 되면 다음 차례에 다시 본다.
     *
     * <p>설정이 없거나 이 서버가 아는 것보다 옛 버전이면 Redis 가 재시작해 잃었거나, 옛 버전을 아는 서버가 먼저 다시 올린
     * 것이다. 이 서버가 아는 마지막 설정을 그 버전 그대로 다시 올린다 — 다른 서버들도 같은 버전을 알고 있으므로 아무것도
     * 바뀌지 않고, 다음 변경은 그 위에서 이어진다. 여러 서버가 동시에 올려도 "없거나 더 낮을 때만" 이라 가장 새 버전이 남는다.
     * 더 새 버전이거나 같은 버전의 다른 변경이면 Redis 를 따른다.
     */
    private void catchUp(Consumer<MockConfigStore.Applied> onChange) {
        try {
            List<Object> head = redis.opsForHash().multiGet(CONFIG_KEY, List.of("version", "changeId"));
            Stamp mine = known.stamp();
            if (head.get(0) == null || Integer.parseInt(head.get(0).toString()) < mine.version()) {
                restore();
                return;
            }
            Stamp stored = new Stamp(Integer.parseInt(head.get(0).toString()), Objects.toString(head.get(1), ""));
            if (mine.behind(stored)) {
                reload(onChange);
            }
        } catch (RedisConnectionFailureException | QueryTimeoutException e) {
            // Redis 에 닿지 못한 것(연결 실패 · 시간 초과)만 경고로 넘긴다 — 결함 저장소(RedisFaultStorage)와 같은 기준이다.
            log.warn("공유 설정 확인 실패(Redis 에 닿지 못함) — 다음 차례에 다시 본다: {}", e.toString());
        } catch (RuntimeException e) {
            // 그 밖은 버그다(저장된 모양이 바뀌어 읽지 못하는 경우 등). 경고 한 줄로 삼키면 설정이 조용히 안 맞춰지므로 스택과 함께
            // error 로 남긴다. 다시 던지지는 않는다 — 예약 작업은 예외를 던지면 다음 차례부터 멈춘다.
            log.error("공유 설정 확인 중 예상하지 못한 오류 — 다음 차례에 다시 본다", e);
        }
    }

    private void restore() {
        Known last = known;
        if (last.applied() == null) {
            return;
        }
        Long written = redis.execute(RESTORE, List.of(CONFIG_KEY), String.valueOf(last.stamp().version()),
                last.stamp().changeId(), write(last.applied().snapshot()), last.applied().appliedAt().toString());
        if (written != null && written == 1) {
            log.warn("공유 설정이 Redis 에 없거나 옛것이라 마지막 설정을 다시 올렸다 configVersion={}",
                    last.stamp().version());
        }
    }

    /**
     * Redis 에 있는 것을 따라야 할 때만 기록한다. 비교와 기록을 한 번에 한다 — 변경(요청 스레드)과 다시 읽기(알림 ·
     * 안전망)가 따로 비교하고 따로 쓰면, 5 를 쓴 뒤 4 가 덮어 아는 버전이 내려간다. {@link #reload} 와 같은 잠금이다.
     *
     * @return 기록했는가
     */
    private synchronized boolean remember(Known next) {
        if (!known.stamp().behind(next.stamp())) {
            return false;
        }
        known = next;
        return true;
    }

    private synchronized void reload(Consumer<MockConfigStore.Applied> onChange) {
        Known stored = read(redis.opsForHash().multiGet(CONFIG_KEY, FIELDS));
        if (!remember(stored)) {
            return;
        }
        onChange.accept(stored.applied());
        log.info("다른 서버가 바꾼 설정을 반영한다 configVersion={} failureRate={} failureMode={}",
                stored.stamp().version(), stored.applied().snapshot().failureRate(),
                stored.applied().snapshot().failureMode());
    }

    /** {@code [version, changeId, body, appliedAt]} 를 읽는다. */
    private Known read(List<?> stored) {
        int version = Integer.parseInt(String.valueOf(stored.get(0)));
        String changeId = Objects.toString(stored.get(1), "");
        Body body = json.readValue(String.valueOf(stored.get(2)), Body.class);
        Instant appliedAt = Instant.parse(String.valueOf(stored.get(3)));
        return new Known(new Stamp(version, changeId), new MockConfigStore.Applied(body.toSnapshot(version), appliedAt));
    }

    /** 알림은 {@code 버전:변경ID} 다. */
    private static Stamp parseAnnouncement(String message) {
        int colon = message.indexOf(':');
        return colon < 0
                ? new Stamp(Integer.parseInt(message), "")
                : new Stamp(Integer.parseInt(message.substring(0, colon)), message.substring(colon + 1));
    }

    private static String newChangeId() {
        return UUID.randomUUID().toString();
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
     * Redis 에 두는 모양. 버전 · 변경 ID 는 따로 둔다(스크립트가 정하므로). 열거형은 이름으로, 꼬리는 칸 셋으로 펴서 둔다 —
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
