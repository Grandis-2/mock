package com.grandis.nova.mockapi.global.chaos;

import com.grandis.nova.mockapi.global.config.MockProperties;
import com.grandis.nova.mockapi.global.error.ErrorCode;
import com.grandis.nova.mockapi.global.error.MockException;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

/**
 * 설정된 지연을 평균으로 기다리고, 확률에 걸리면 일시 실패를 만든다.
 *
 * <p>느리고 가끔 실패하는 외부 시스템을 흉내 내는 부분이다. 요구사항이 Mock 동작으로 정한
 * <b>"평균 500ms 지연 · 5% 확률 실패"</b> 가 여기서 나온다.
 *
 * <p>지연은 <b>모든 요청</b>이 겪고, 실패는 <b>확률에 걸린 일부</b>만 겪는다. 둘은 배타적이지
 * 않다 — 실패한 요청도 지연은 이미 겪은 뒤다.
 *
 * <p>DB 를 건드리지 않는다. 트랜잭션 밖에서 불리므로 여기서 커넥션을 빌리면 대기 시간만큼
 * 커넥션이 묶여 풀이 마른다.
 */
@Component
public class DefaultFailureInjector implements FailureInjector {

    private final ConnectionDropper dropper;
    private final MockProperties properties;

    public DefaultFailureInjector(ConnectionDropper dropper, MockProperties properties) {
        this.dropper = dropper;
        this.properties = properties;
    }

    @Override
    public void apply(ConfigSnapshot snapshot) {
        sleep(snapshot.registerLatencyMs());

        if (!rolledFailure(snapshot.failureRate())) {
            return;
        }

        switch (snapshot.failureMode()) {
            // 워커는 이것을 "일시 실패" 로 읽고 그대로 재시도한다. 커밋 전이라 아무것도 저장되지 않는다.
            case HTTP_5XX -> throw new MockException(ErrorCode.UPSTREAM_UNAVAILABLE);
            // 워커는 결과를 모른다(UNKNOWN). by-key 조회로 확인한 뒤에 재시도해야 한다.
            case TIMEOUT -> dropper.drop("failureMode=TIMEOUT 주사위에 걸렸다");
        }
    }

    /**
     * 주사위를 굴린다.
     *
     * <p>{@code nextDouble()} 은 [0, 1) 이라 1.0 이 나오지 않는다. 그래서 난수를 왼쪽에 둔다 —
     * 뒤집으면 실패율 0 일 때 {@code 0.xx < 0} 이 아니라 {@code 0 < 0.xx} 가 되어 전부 실패한다.
     */
    private boolean rolledFailure(double failureRate) {
        return ThreadLocalRandom.current().nextDouble() < failureRate;
    }

    private void sleep(int meanLatencyMs) {
        if (meanLatencyMs <= 0) {
            return;
        }
        try {
            Thread.sleep(jittered(meanLatencyMs));
        } catch (InterruptedException e) {
            // 인터럽트 상태를 되살려 둔다. 삼키면 종료 신호가 묻힌다.
            Thread.currentThread().interrupt();
            throw new MockException(ErrorCode.UPSTREAM_UNAVAILABLE, "지연 대기 중 중단됐다");
        }
    }

    /**
     * 설정값을 <b>평균</b>으로 삼아 균등분포로 흔든다. 범위는 {@code 평균 × (1 ∓ 지터)} 다.
     *
     * <p>지터가 0 이면 흔들지 않는다. 대기 시간을 단정해야 하는 단위 시험에서만 그렇게 둔다.
     *
     * <p>지터는 설정에서 0 ~ 1 로 막는다({@link MockProperties}). 1 이면 하한이 정확히 0 이고, 그보다
     * 크면 하한만 잘려 평균이 올라가기 때문이다. 하한을 0 에서 한 번 더 자르는 것은 방어일 뿐이다.
     *
     * <p>시험이 직접 부를 수 있게 package-private 로 둔다. {@code Thread.sleep} 을 수만 번 재면
     * 스케줄러 오차가 분포보다 커져 무엇을 본 것인지 알 수 없다.
     */
    long jittered(int meanMs) {
        double jitter = properties.latencyJitter();
        if (jitter <= 0) {
            return meanMs;
        }
        long low = Math.max(0, Math.round(meanMs * (1 - jitter)));
        long high = Math.round(meanMs * (1 + jitter));
        // 상한을 포함시켜야 분포가 평균을 중심으로 대칭이 된다.
        return ThreadLocalRandom.current().nextLong(low, high + 1);
    }
}
