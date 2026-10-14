package com.grandis.nova.mockapi.draw.domain;

/**
 * 럭키드로우 회차의 상태. DB 의 {@code ck_draw_status} 가 이 둘만 허용한다.
 *
 * <p>마감 상태는 두지 않는다. Mock 은 마감 시각을 모르고, 추첨 전까지 오는 응모는 모두 받는다(be 계약 「마감 뒤에도 온다」).
 */
public enum DrawStatus {

    /** 응모를 받는 중. 처음 보는 회차 id 로 응모가 오면 이 상태로 만든다. */
    OPEN,

    /** 추첨했다. 당첨 인원 · 추첨 시각이 채워져 있고, 이 뒤로 오는 새 응모는 거절한다. 다시 열지 않는다. */
    DRAWN
}
