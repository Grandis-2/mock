package com.grandis.nova.mockapi.registration.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface RegistrationRepository extends JpaRepository<Registration, String> {

    /**
     * 키 행을 잠그고 읽는다. 같은 키의 다른 요청이 처리 중이면 그 트랜잭션이 끝날 때까지 기다린다.
     *
     * <p>행이 없으면 잠금이 걸리지 않는다(READ COMMITTED 에는 갭 락이 없다).
     * 같은 새 키의 동시 요청은 INSERT 에서 직렬화되고, 진 쪽은 중복 키(1062)를 받는다.
     * 1062 는 오류가 아니라 정상 분기다 — 다시 이 메서드로 조회해 이어간다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Registration r where r.externalKey = :externalKey")
    Optional<Registration> findByKeyForUpdate(String externalKey);

    /**
     * 잠금 없이 읽으면 커밋 안 된 등록을 보지 못한 채 "없음" 이 나온다.
     * by-key 조회가 트랜잭션 안의 등록을 놓치지 않으려면 공유 잠금으로 기다려야 한다.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select r from Registration r where r.externalKey = :externalKey")
    Optional<Registration> findByKeyForShare(String externalKey);

    Optional<Registration> findByExternalNumber(String externalNumber);
}
