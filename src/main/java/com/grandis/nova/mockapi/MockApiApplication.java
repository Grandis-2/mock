package com.grandis.nova.mockapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration;

// Redis 는 설정 · 결함을 키로 직접 읽고 쓴다(mock.shared-state=redis). 저장소 인터페이스를 쓰지 않으므로 그 스캔을 끈다 —
// 켜 두면 기동마다 JPA 저장소를 Redis 것인지 가리려다 "Could not safely identify store assignment" 를 남긴다.
@SpringBootApplication(exclude = DataRedisRepositoriesAutoConfiguration.class)
@ConfigurationPropertiesScan
public class MockApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockApiApplication.class, args);
    }
}
