package com.grandis.nova.mockapi.registration.application;

import com.grandis.nova.mockapi.global.error.ErrorCode;
import com.grandis.nova.mockapi.global.error.MockException;
import com.grandis.nova.mockapi.registration.domain.Registration;
import com.grandis.nova.mockapi.registration.domain.RegistrationRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 취소 중 트랜잭션 안의 부분. 한 번 호출이 한 번의 시도다.
 *
 * <p>등록과 같은 이유로 서비스와 나눈다({@link RegistrationWriter} 참고). 중복 키가 나면 이 트랜잭션은
 * 롤백 전용이 되므로, 재시도는 바깥에서 새 트랜잭션으로 한다.
 */
@Component
public class CancellationWriter {

    private final RegistrationRepository repository;

    public CancellationWriter(RegistrationRepository repository) {
        this.repository = repository;
    }

    /**
     * @param externalNumber 함께 받은 번호. 없으면 null
     * @throws MockException 키와 번호가 서로 다른 등록을 가리킴(400)
     * @throws org.springframework.dao.DataIntegrityViolationException 중복 키. 바깥에서 새 트랜잭션으로 다시 부른다
     */
    @Transactional
    public CancelResult cancelOnce(String externalKey, String externalNumber) {
        // 1. 키 행을 잠근다. 같은 키의 등록 · 취소와 직렬화된다.
        Optional<Registration> existing = repository.findByKeyForUpdate(externalKey);
        if (externalNumber != null) {
            requireSameRegistration(externalNumber, existing);
        }

        // 등록 시각과 같은 이유로 밀리초로 자른다. 응답과 DB 에 남은 값이 같아야 한다.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        // 2~3. 활성 등록이면 끄고, 이미 취소된 행이면 그대로 둔다(시각을 덮어쓰지 않는다).
        if (existing.isPresent()) {
            Registration registration = existing.get();
            boolean hadActive = registration.isActive();
            registration.cancel(now);
            return new CancelResult(registration, hadActive);
        }

        // 4. 등록이 없어도 취소 표식을 남긴다. 표식이 없으면 "취소 성공" 이라고 답해놓고 늦게 도착한
        // 등록이 살아난다. 같은 새 키를 등록이 먼저 INSERT 했으면 여기서 중복 키가 난다.
        Registration marker = Registration.cancelMarker(externalKey, now);
        repository.saveAndFlush(marker);
        return new CancelResult(marker, false);
    }

    /**
     * 키와 번호가 같은 등록을 가리키는지 본다. 다르면 워커의 버그다 — 조용히 키만 보고 취소하면 워커는
     * 그 번호를 취소했다고 믿는데 실제로는 다른 등록이 꺼지고, 그 번호의 등록은 살아남는다.
     *
     * <p>키에 번호가 없고(미등록 · 표식만) 그 번호의 등록도 없으면 불일치가 아니다. 키로 표식을 남긴다.
     * {@code reset} 뒤에 워커가 옛 번호를 들고 오는 경우다 — 여기서 막으면 표식을 남길 기회가 사라진다.
     *
     * <p>번호는 잠금 없이 읽는다. 워커가 아는 번호는 모두 이미 커밋된 번호다({@link CancellationService} 참고).
     */
    private void requireSameRegistration(String externalNumber, Optional<Registration> keyRow) {
        String keysNumber = keyRow.map(Registration::externalNumber).orElse(null);
        boolean mismatch = keysNumber != null
                ? !keysNumber.equals(externalNumber)
                : repository.findByExternalNumber(externalNumber).isPresent();
        if (mismatch) {
            throw new MockException(ErrorCode.INVALID_REQUEST,
                    "externalKey 와 externalNumber 가 서로 다른 등록을 가리킵니다.");
        }
    }
}
