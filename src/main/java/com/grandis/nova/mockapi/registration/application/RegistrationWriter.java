package com.grandis.nova.mockapi.registration.application;

import com.grandis.nova.mockapi.global.error.ErrorCode;
import com.grandis.nova.mockapi.global.error.MockException;
import com.grandis.nova.mockapi.registration.domain.ExternalNumberGenerator;
import com.grandis.nova.mockapi.registration.domain.Registration;
import com.grandis.nova.mockapi.registration.domain.RegistrationRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 등록 처리 중 트랜잭션 안의 부분(명세 3~6단계). 한 번 호출이 한 번의 시도다.
 *
 * <p>{@link RegistrationService} 와 나눈 이유 — 중복 키가 나면 그 트랜잭션은 롤백 전용이 되어
 * 같은 트랜잭션 안에서 다시 시도해도 커밋되지 않는다. 트랜잭션을 나갔다가 새로 시작해야 하므로
 * 재시도 루프는 바깥(서비스)에, 한 번의 시도는 여기에 둔다. 같은 클래스 안에서 부르면 프록시를
 * 거치지 않아 {@code @Transactional} 이 먹지 않으므로 빈을 따로 둔다.
 */
@Component
public class RegistrationWriter {

    private final RegistrationRepository repository;
    private final ExternalNumberGenerator numbers;

    public RegistrationWriter(RegistrationRepository repository, ExternalNumberGenerator numbers) {
        this.repository = repository;
        this.numbers = numbers;
    }

    /**
     * @throws MockException 취소된 키(409) · 같은 키 다른 내용(422)
     * @throws org.springframework.dao.DataIntegrityViolationException 중복 키. 바깥에서 새 트랜잭션으로 다시 부른다
     */
    @Transactional
    public Attempt attemptOnce(String key, RegisterCommand command) {
        // 3. 키 행을 잠그고 읽는다. 행이 없으면 잠금이 걸리지 않는다(READ COMMITTED 에는 갭 락이 없다).
        var existing = repository.findByKeyForUpdate(key);
        if (existing.isPresent()) {
            return judgeExisting(existing.get(), command);
        }

        // 6. 새로 만든다. 번호는 시도마다 새로 뽑는다 — 번호가 겹쳐 재시도할 때 같은 번호면 또 부딪힌다.
        // 밀리초로 자른다. DB 에 저장된 값과 첫 응답의 값이 같아야 재생 응답과 어긋나지 않는다.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Registration created = Registration.active(
                key, numbers.next(now),
                command.customerId(), command.productId(), command.sku(),
                now);
        // 여기서 INSERT 를 내보낸다. 중복 키가 커밋 시점이 아니라 이 줄에서 나야 원인이 분명하다.
        repository.saveAndFlush(created);
        return new Attempt(created, false);
    }

    private Attempt judgeExisting(Registration registration, RegisterCommand command) {
        // 4. 취소 표식이 재생보다 먼저다. 순서가 반대면 "등록 → 취소 → 재등록" 이 201 로 재생된다.
        if (registration.isCanceled()) {
            throw new MockException(ErrorCode.KEY_CANCELED,
                    ErrorCode.KEY_CANCELED.defaultMessage(), registration.externalNumber());
        }

        // 5. 같은 신청이면 저장된 결과를 재생하고, 아니면 기존 등록을 건드리지 않고 거절한다.
        List<String> differing = registration.differingFields(
                command.customerId(), command.productId(), command.sku());
        if (!differing.isEmpty()) {
            throw new MockException(ErrorCode.KEY_PAYLOAD_MISMATCH,
                    "같은 키로 다른 내용이 요청되었습니다. " + String.join(", ", differing) + " 이(가) 다릅니다.",
                    registration.externalNumber());
        }
        return new Attempt(registration, true);
    }

    /** 한 번의 시도 결과. */
    public record Attempt(Registration registration, boolean replayed) {
    }
}
