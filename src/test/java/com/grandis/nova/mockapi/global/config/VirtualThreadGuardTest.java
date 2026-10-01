package com.grandis.nova.mockapi.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 가상 스레드 설정이 빠지면 Mock 이 뜨지 않아야 한다. 빠져도 오류 없이 플랫폼 스레드로 도는 것을 막는다.
 *
 * <p>앱 전체를 띄우지 않고 검사 하나만 올린다. 설정 값만 바꿔 가며 기동 성패를 보려면 컨텍스트를 매번 새로
 * 만들어야 하는데, 전체 컨텍스트로 하면 DB 까지 붙어 느리다.
 */
class VirtualThreadGuardTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(VirtualThreadGuard.class);

    @Test
    @DisplayName("가상 스레드가 켜져 있으면 뜬다")
    void startsWithVirtualThreads() {
        runner.withPropertyValues("spring.threads.virtual.enabled=true")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("설정이 빠지면 뜨지 않는다 — 복사한 application.yml 에서 한 줄이 빠진 경우")
    void refusesWhenMissing() {
        runner.run(context -> assertThat(context).getFailure()
                .rootCause().hasMessageContaining("spring.threads.virtual.enabled=true"));
    }

    @Test
    @DisplayName("명시적으로 끄면 뜨지 않는다")
    void refusesWhenDisabled() {
        runner.withPropertyValues("spring.threads.virtual.enabled=false")
                .run(context -> assertThat(context).getFailure()
                        .rootCause().hasMessageContaining("가상 스레드가 꺼져 있습니다"));
    }
}
