package com.grandis.nova.mockapi.registration;

import com.grandis.nova.mockapi.global.error.ErrorCode;
import com.grandis.nova.mockapi.global.error.MockException;
import org.springframework.stereotype.Service;

/**
 * 예약 취소. 명세의 시스템 처리 1~8단계.
 *
 * <p><b>지연 · 실패를 주입하지 않는다.</b> 과제가 취소를 "항상 성공" 으로 가정한다. 업무상 거절도 없다 —
 * 미등록 키도, 이미 취소된 대상도 성공이다.
 *
 * <p>등록과 마찬가지로 이 클래스에는 {@code @Transactional} 을 붙이지 않는다. 중복 키 재시도는 트랜잭션을
 * 나와서 해야 한다.
 */
@Service
public class CancellationService {

    private final RegistrationRepository repository;
    private final CancellationWriter writer;

    public CancellationService(RegistrationRepository repository, CancellationWriter writer) {
        this.repository = repository;
        this.writer = writer;
    }

    /**
     * 키가 있으면 키로, 없으면 번호의 등록을 찾아 그 키로 취소한다. 잠금과 표식은 모두 키 단위다.
     *
     * @param externalKey    없으면 null. 둘 중 하나는 있어야 한다
     * @param externalNumber 없으면 null
     * @throws MockException 번호만 받았는데 그 번호의 등록이 없음(404) · 키와 번호가 서로 다른 등록(400)
     */
    public CancelResult cancel(String externalKey, String externalNumber) {
        String key = externalKey != null ? externalKey : keyOf(externalNumber);
        return DuplicateKeyRetry.run(key, () -> writer.cancelOnce(key, externalNumber));
    }

    /**
     * 번호만 받은 경우 그 등록의 키. 키가 없으니 표식을 남길 수 없고, 번호는 Mock 이 등록할 때만
     * 발급하므로 그 번호로 늦게 오는 등록도 없다. 없는 번호는 404 로 알린다.
     */
    private String keyOf(String externalNumber) {
        return repository.findByExternalNumber(externalNumber)
                .map(Registration::externalKey)
                .orElseThrow(() -> new MockException(ErrorCode.NOT_FOUND, "등록되지 않았습니다."));
    }
}
