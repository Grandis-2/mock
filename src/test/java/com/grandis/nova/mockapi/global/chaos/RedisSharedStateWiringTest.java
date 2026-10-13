package com.grandis.nova.mockapi.global.chaos;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@code mock.shared-state=redis} 로 앱 전체를 띄웠을 때 Redis 공유가 실제로 붙는지 본다. 한 대(memory, 기본)로 도는
 * 다른 시험들은 Redis 없이 뜨므로, 이 조합이 맞게 엮이는지는 여기서만 확인된다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = "mock.shared-state=redis")
class RedisSharedStateWiringTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8").withExposedPorts(6379);

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private MockConfigStore store;

    @Autowired
    private StringRedisTemplate redis;

    @Test
    @DisplayName("redis 모드면 기동할 때 공유 설정을 올리고, 바꾸면 Redis 의 버전이 오른다")
    void configLivesInRedis() {
        assertThat(context.getBeansOfType(ConfigSync.class)).containsOnlyKeys("redisConfigSync");
        assertThat(redis.opsForHash().get(RedisConfigSync.CONFIG_KEY, "version"))
                .isEqualTo(String.valueOf(store.snapshot().configVersion()));

        int before = store.snapshot().configVersion();
        store.update(500, 0.3, FailureMode.HTTP_5XX);

        assertThat(redis.opsForHash().get(RedisConfigSync.CONFIG_KEY, "version"))
                .isEqualTo(String.valueOf(before + 1));
        assertThat(store.snapshot().failureRate()).isEqualTo(0.3);
    }
}
