package com.grandis.nova.mockapi.global.config;

import org.springframework.boot.thread.Threading;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 가상 스레드가 꺼져 있으면 Mock 을 띄우지 않는다.
 *
 * <p>Mock 은 일부러 기다리는 서버다. 등록마다 평균 500ms 를 자고, {@code TIMEOUT} 과 결함은 응답 없이
 * 수 초를 붙잡는다. 부하에서 수천 건이 동시에 대기하는데, 플랫폼 스레드(톰캣 기본 200개)로 돌면 자는
 * 요청이 스레드를 다 차지해 Mock 이 병목이 된다. 그러면 본 서비스 측정에서 Mock 을 빼고 읽을 수 없다.
 *
 * <p>설정은 {@code spring.threads.virtual.enabled} 한 줄이다. 각자 복사해 쓰는 {@code application.yml} 에만
 * 있어서, 빠져도 오류 없이 플랫폼 스레드로 돈다. 격리 수준과 달리 트랜잭션에 선언할 수 없는 값이라
 * 기동할 때 확인한다.
 */
@Component
class VirtualThreadGuard {

    VirtualThreadGuard(Environment environment) {
        if (!Threading.VIRTUAL.isActive(environment)) {
            throw new IllegalStateException("가상 스레드가 꺼져 있습니다. spring.threads.virtual.enabled=true 로 띄우세요"
                    + " (application.yml.example 참고). 플랫폼 스레드로는 지연 · TIMEOUT 으로 대기하는 요청이"
                    + " 스레드를 다 차지해 Mock 이 병목이 됩니다.");
        }
    }
}
