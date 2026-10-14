package com.grandis.nova.mockapi.draw.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DrawEntryRepository extends JpaRepository<DrawEntry, String> {

    /**
     * 그 회차에 그 회원의 응모가 있는가. 회차 행을 잠근 뒤에 부르므로 같은 회차의 다른 응모와 겹치지 않는다. 겹쳐도
     * {@code uq_draw_entry_customer} 가 마지막에 막는다.
     */
    boolean existsByDrawIdAndCustomerId(String drawId, UUID customerId);
}
