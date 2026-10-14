package com.grandis.nova.mockapi.draw.application;

import com.grandis.nova.mockapi.draw.application.DrawWriter.Attempt;
import com.grandis.nova.mockapi.global.chaos.ConfigProvider;
import com.grandis.nova.mockapi.global.chaos.ConfigSnapshot;
import com.grandis.nova.mockapi.global.chaos.FailureInjector;
import org.springframework.stereotype.Service;

/**
 * 럭키드로우 추첨. 응모와 같은 순서로 부른다.
 *
 * <pre>
 * [트랜잭션 밖]  1. 지연 대기   2. 실패율 판정        ← FailureInjector (등록 · 응모와 같은 설정)
 * [트랜잭션 안]  3~6. 회차 잠금 · 재생 · 수 확인 · 뽑기   ← DrawWriter
 * </pre>
 *
 * <p>실패는 커밋 전이라 추첨하지 않는다 — worker 는 같은 요청을 다시 보내면 된다. {@code @Transactional} 을 붙이지 않는
 * 이유는 응모와 같다(지연 대기가 DB 커넥션을 묶지 않게).
 */
@Service
public class DrawService {

    private final ConfigProvider configProvider;
    private final FailureInjector failureInjector;
    private final DrawWriter writer;

    public DrawService(ConfigProvider configProvider, FailureInjector failureInjector, DrawWriter writer) {
        this.configProvider = configProvider;
        this.failureInjector = failureInjector;
        this.writer = writer;
    }

    public DrawResult draw(String drawId, DrawCommand command) {
        ConfigSnapshot snapshot = configProvider.snapshot();
        failureInjector.apply(snapshot);

        Attempt attempt = writer.drawOnce(drawId, command);
        return new DrawResult(attempt.state(), attempt.replayed(), snapshot.configVersion());
    }
}
