package com.grandis.nova.mockapi.draw.application;

import com.grandis.nova.mockapi.draw.application.DrawEntryWriter.Attempt;
import com.grandis.nova.mockapi.global.chaos.ConfigProvider;
import com.grandis.nova.mockapi.global.chaos.ConfigSnapshot;
import com.grandis.nova.mockapi.global.chaos.FailureInjector;
import com.grandis.nova.mockapi.global.persistence.DuplicateKeyRetry;
import org.springframework.stereotype.Service;

/**
 * 럭키드로우 응모. 등록과 같은 순서로 부른다.
 *
 * <pre>
 * [트랜잭션 밖]  1. 지연 대기   2. 실패율 판정              ← FailureInjector (등록과 같은 설정)
 * [트랜잭션 안]  3~6. 회차 잠금(없으면 만듦) · 재생 · 추첨 여부 · 같은 회원 · 저장  ← DrawEntryWriter (시도 한 번)
 *               중복 키면 트랜잭션을 나와 3단계부터 다시
 * </pre>
 *
 * <p>등록과 같은 이유로 이 클래스에는 {@code @Transactional} 을 붙이지 않는다 — 붙이면 지연 대기가 트랜잭션 안으로 들어가 그만큼
 * DB 커넥션이 묶인다. 키별 결함(응답 유실 · 느린 성공)은 응모에 걸지 않는다(API 안 「범위 밖」).
 */
@Service
public class DrawEntryService {

    private final ConfigProvider configProvider;
    private final FailureInjector failureInjector;
    private final DrawEntryWriter writer;

    public DrawEntryService(ConfigProvider configProvider, FailureInjector failureInjector, DrawEntryWriter writer) {
        this.configProvider = configProvider;
        this.failureInjector = failureInjector;
        this.writer = writer;
    }

    public DrawEntryResult enter(String entryId, DrawEntryCommand command) {
        // 1~2. 이 시도가 쓸 설정을 먼저 얼린다. 실패는 커밋 전이라 아무것도 저장되지 않는다.
        ConfigSnapshot snapshot = configProvider.snapshot();
        failureInjector.apply(snapshot);

        // 3~6. 중복 키면 트랜잭션을 나와 다시 — 처음 보는 회차를 두 응모가 동시에 만들려 한 경우 등
        Attempt attempt = DuplicateKeyRetry.run(entryId, () -> writer.enterOnce(entryId, command));
        return new DrawEntryResult(attempt.entry(), attempt.replayed(), snapshot.configVersion());
    }
}
