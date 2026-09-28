package com.grandis.nova.mockapi.registration;

import com.grandis.nova.mockapi.global.chaos.ConfigProvider;
import com.grandis.nova.mockapi.global.chaos.ConfigSnapshot;
import com.grandis.nova.mockapi.global.chaos.ConnectionDropper;
import com.grandis.nova.mockapi.global.chaos.FailureInjector;
import com.grandis.nova.mockapi.global.chaos.FaultHook;
import com.grandis.nova.mockapi.registration.RegistrationWriter.Attempt;
import com.grandis.nova.mockapi.registration.dto.RegisterRequest;
import org.springframework.stereotype.Service;

/**
 * 예약 등록. 명세의 처리 순서 1~7단계를 이 순서대로 부른다.
 *
 * <pre>
 * [트랜잭션 밖]  1. 지연 대기   2. 실패율 판정          ← FailureInjector
 * [트랜잭션 안]  3~6. 잠금 · 취소 표식 · 재생 · 저장    ← RegistrationWriter (시도 한 번)
 *               중복 키면 트랜잭션을 나와 3단계부터 다시
 * [커밋 후]     7. 결함이 걸려 있으면 응답 없이 끊기   ← FaultHook · ConnectionDropper
 * </pre>
 *
 * <p><b>이 클래스에는 {@code @Transactional} 을 붙이지 않는다.</b> 붙이면 지연 대기가 트랜잭션 안으로
 * 들어가 그만큼 DB 커넥션이 묶인다. 지연 2초 · 풀 20개면 10 RPS 에서도 풀이 마른다.
 */
@Service
public class RegistrationService {

    private final ConfigProvider configProvider;
    private final FailureInjector failureInjector;
    private final RegistrationWriter writer;
    private final FaultHook faultHook;
    private final ConnectionDropper connectionDropper;

    public RegistrationService(ConfigProvider configProvider, FailureInjector failureInjector,
                               RegistrationWriter writer, FaultHook faultHook,
                               ConnectionDropper connectionDropper) {
        this.configProvider = configProvider;
        this.failureInjector = failureInjector;
        this.writer = writer;
        this.faultHook = faultHook;
        this.connectionDropper = connectionDropper;
    }

    public RegisterResult register(String key, RegisterRequest request) {
        // 1~2. 이 시도가 쓸 설정을 먼저 얼린다. 처리 도중 설정이 바뀌어도 이 값으로 끝난다.
        // 실패는 커밋 전이라 아무것도 저장되지 않는다. TIMEOUT 이면 여기서 응답 없이 끝난다.
        ConfigSnapshot snapshot = configProvider.snapshot();
        failureInjector.apply(snapshot);

        // 3~6. 중복 키면 트랜잭션을 나와 3단계부터 다시
        Attempt attempt = DuplicateKeyRetry.run(key, () -> writer.attemptOnce(key, request));

        // 7. 새로 커밋한 경우에만 발동한다. 재생은 커밋이 없어 "커밋 후 응답 유실" 이 아니다.
        if (!attempt.replayed() && faultHook.consumeResponseLost(key)) {
            connectionDropper.drop("RESPONSE_LOST_AFTER_COMMIT: " + key);
        }
        return new RegisterResult(attempt.registration(), attempt.replayed(), snapshot.configVersion());
    }
}
