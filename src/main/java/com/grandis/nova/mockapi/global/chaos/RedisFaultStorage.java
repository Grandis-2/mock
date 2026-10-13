package com.grandis.nova.mockapi.global.chaos;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis 에 결함을 둔다. 여러 대로 띄울 때 쓴다({@code mock.shared-state=redis}).
 *
 * <p>해시 {@value #FAULTS_KEY} 하나에 둔다(칸 = 외부 키, 값 = {@code 종류|대기ms}). 한 키로 모아 두어 초기화가 키 하나
 * 지우기로 끝난다.
 *
 * <p><b>꺼내기는 Lua 스크립트로 한 번에 한다.</b> "종류가 맞으면 지우고 돌려준다" 를 나눠 하면 여러 서버가 같은 키의
 * 등록을 동시에 받았을 때 결함이 두 번 발동한다.
 *
 * <p><b>Redis 에 닿지 못하면(연결 실패 · 시간 초과) 꺼내기는 "결함 없음" 으로 넘어간다.</b> 결함은 시험용 장치라, Redis
 * 장애로 등록 자체가 실패하게 두지 않는다. 그 밖의 오류는 버그라 그대로 알린다. 걸기 · 지우기(결함 API · 초기화)도 실패를
 * 그대로 알린다 — 걸렸다고 믿고 시험하면 안 되므로.
 */
@Component
@ConditionalOnProperty(prefix = "mock", name = "shared-state", havingValue = "redis")
public class RedisFaultStorage implements FaultStorage {

    static final String FAULTS_KEY = "mock:faults";

    private static final Logger log = LoggerFactory.getLogger(RedisFaultStorage.class);

    /** 그 종류면 지우고 값을 돌려준다. 아니면 그대로 두고 nil. */
    private static final RedisScript<String> TAKE = new DefaultRedisScript<>("""
            local value = redis.call('HGET', KEYS[1], ARGV[1])
            if not value then
              return nil
            end
            if string.sub(value, 1, string.len(ARGV[2]) + 1) ~= ARGV[2] .. '|' then
              return nil
            end
            redis.call('HDEL', KEYS[1], ARGV[1])
            return value
            """, String.class);

    private final StringRedisTemplate redis;

    public RedisFaultStorage(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void put(String externalKey, StoredFault fault) {
        redis.opsForHash().put(FAULTS_KEY, externalKey, fault.type().name() + "|" + fault.delayMs());
    }

    @Override
    public StoredFault take(String externalKey, FaultType type) {
        String value;
        try {
            value = redis.execute(TAKE, List.of(FAULTS_KEY), externalKey, type.name());
        } catch (RedisConnectionFailureException | QueryTimeoutException e) {
            // 닿지 못한 것(연결 실패 · 시간 초과)만 결함 없음으로 넘긴다. 스크립트 오류 같은 버그까지 삼키면 결함이 조용히
            // 안 걸리는 원인을 찾을 수 없다.
            log.warn("공유 결함 확인 실패 — 결함 없음으로 처리한다 key={} type={}: {}", externalKey, type, e.toString());
            return null;
        }
        if (value == null) {
            return null;
        }
        return new StoredFault(type, Long.parseLong(value.substring(value.indexOf('|') + 1)));
    }

    @Override
    public boolean contains(String externalKey) {
        return redis.opsForHash().hasKey(FAULTS_KEY, externalKey);
    }

    @Override
    public void clear() {
        redis.delete(FAULTS_KEY);
    }
}
