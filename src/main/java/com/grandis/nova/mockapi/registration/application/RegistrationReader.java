package com.grandis.nova.mockapi.registration.application;

import com.grandis.nova.mockapi.global.error.ErrorCode;
import com.grandis.nova.mockapi.global.error.MockException;
import com.grandis.nova.mockapi.registration.domain.Registration;
import com.grandis.nova.mockapi.registration.domain.RegistrationRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 등록 원장 조회. 번호 조회와 키 조회를 맡는다.
 *
 * <p>지연·실패를 적용하지 않는다(명세). 워커가 결과를 확인하려고 부르는 길이라, 실패율을 올린 상태에서도
 * 막히면 안 된다.
 */
@Component
public class RegistrationReader {

    private final RegistrationRepository repository;

    public RegistrationReader(RegistrationRepository repository) {
        this.repository = repository;
    }

    /**
     * 번호로 한 건. 취소된 등록도 숨기지 않는다.
     *
     * @throws MockException 없는 번호(404)
     */
    @Transactional(readOnly = true)
    public Registration findByNumber(String externalNumber) {
        return repository.findByExternalNumber(externalNumber)
                .orElseThrow(() -> new MockException(ErrorCode.NOT_FOUND, "등록되지 않았습니다."));
    }

    /**
     * 키로 한 행. 등록이든 취소 표식이든 그 키의 행을 돌려준다.
     *
     * <p><b>공유 잠금으로 읽는다.</b> 같은 키의 등록이 커밋되기 전이면 끝날 때까지 기다린다. 그냥 읽으면
     * 진행 중인 등록을 못 본 채 404 가 나가고, 워커는 그걸 "등록 안 됨" 으로 믿고 재등록해 중복 등록이 된다.
     *
     * <p>지연은 등록 트랜잭션 밖이라 잠금을 쥐고 있지 않다. 등록 지연을 크게 잡아도 이 조회는 그만큼
     * 기다리지 않고, 지연 중인 등록은 404 로 보인다. 그래서 404 는 "지금 없음" 이지 "앞으로도 없음" 이
     * 아니다 — 워커는 같은 키로 다시 보내고, 그만둘 때는 같은 키로 먼저 취소한다(명세의 포기 규칙).
     *
     * @throws MockException 등록·취소 표식 어느 것도 없는 키(404)
     */
    @Transactional(readOnly = true)
    public Registration findByKey(String externalKey) {
        return repository.findByKeyForShare(externalKey)
                .orElseThrow(() -> new MockException(ErrorCode.NOT_FOUND, "키에 대한 기록이 없습니다."));
    }
}
