package com.grandis.nova.mockapi.draw.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface DrawRepository extends JpaRepository<Draw, String> {

    /**
     * 회차 행을 잠그고 읽는다. 같은 회차의 응모 · 추첨이 처리 중이면 그 트랜잭션이 끝날 때까지 기다린다 — 그래서 한 회차의
     * 응모 · 추첨은 한 줄로 선다.
     *
     * <p>행이 없으면 잠금이 걸리지 않는다(READ COMMITTED 에는 갭 락이 없다). 처음 보는 회차로 두 응모가 동시에 오면 둘 다
     * "없음" 을 보고 회차를 만들려 하고, 진 쪽은 중복 키(1062)를 받는다 — 정상 분기라 새 트랜잭션으로 다시 이 메서드부터 한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Draw d where d.drawId = :drawId")
    Optional<Draw> findByIdForUpdate(String drawId);
}
