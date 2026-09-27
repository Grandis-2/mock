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
     *
     * <p>키 조회와 달리 <b>잠금 없이 읽어도 된다.</b> 키 조회가 {@code FOR SHARE} 인 건 진행 중인 등록을
     * 못 본 채 404 를 주면 안 되기 때문인데, 번호는 그럴 수가 없다. 번호는 등록 트랜잭션 안에서 만들어지고
     * 201 은 커밋한 뒤에 나간다(응답 유실 결함도 커밋 뒤, 지연 · 실패는 트랜잭션 전이라 번호가 없다).
     * 그래서 워커가 아는 번호는 모두 이미 커밋된 번호다.
     */
    private String keyOf(String externalNumber) {
        return repository.findByExternalNumber(externalNumber)
                .map(Registration::externalKey)
                .orElseThrow(() -> new MockException(ErrorCode.NOT_FOUND, "등록되지 않았습니다."));
    }
}
