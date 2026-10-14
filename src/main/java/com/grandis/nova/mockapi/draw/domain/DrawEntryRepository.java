package com.grandis.nova.mockapi.draw.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DrawEntryRepository extends JpaRepository<DrawEntry, String> {

    /** 그 회차로 받은 응모 수. {@code ix_draw_entry_winner}(draw_id, winner_rank) 의 앞 칸으로 센다. */
    long countByDrawId(String drawId);

    /**
     * 그 회차의 응모 id 전부. 추첨 후보다. 응모 id 순으로 읽어 같은 씨앗의 난수면 같은 당첨이 나오게 한다(시험) — 순서가 뽑힐
     * 확률을 바꾸지는 않는다.
     */
    @Query("select e.entryId from DrawEntry e where e.drawId = :drawId order by e.entryId")
    List<String> findEntryIds(String drawId);

    /** 그 회차의 당첨 응모 id 를 뽑힌 순서로. 추첨 전이면 비어 있다. */
    @Query("select e.entryId from DrawEntry e where e.drawId = :drawId and e.winnerRank is not null"
            + " order by e.winnerRank")
    List<String> findWinnerEntryIds(String drawId);

    /**
     * 그 회차에 그 회원의 응모가 있는가. 회차 행을 잠근 뒤에 부르므로 같은 회차의 다른 응모와 겹치지 않는다. 겹쳐도
     * {@code uq_draw_entry_customer} 가 마지막에 막는다.
     */
    boolean existsByDrawIdAndCustomerId(String drawId, UUID customerId);
}
