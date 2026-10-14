package com.grandis.nova.mockapi.draw.api;

import com.grandis.nova.mockapi.draw.application.DrawCommand;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 추첨 요청. be worker 가 추첨 요청(DRAW_REQUESTED)을 받아 Mock 이 받은 응모 수를 대조한 뒤 보낸다(be {@code contracts/draw-entry-sync.md}).
 *
 * <p>회차 id 는 경로에 있고 그것이 멱등 키다 — {@code Idempotency-Key} 헤더를 받지 않는다. 래퍼형이다. 기본형이면
 * {@code winnerCount} 를 빼먹었을 때 0 이 들어가 필수 검사를 지나친다. 명세에 없는 필드는 400 이다(StrictJsonConfig).
 *
 * @param winnerCount        당첨 인원(payload.winnerCount). 1 이상, 받은 응모 수보다 작아야 한다(아니면 422)
 * @param expectedEntryCount 기대 응모 수(payload.paidEntries). <b>선택 칸이다.</b> 보내면 Mock 이 회차를 잠근 채 받은 수와 대조하고,
 *                           다르면 409 {@code ENTRY_COUNT_MISMATCH}. 안 보내면 수를 확인하지 않는다 — worker 가 409 를
 *                           "늦춰 다시" 로 읽기 전에는 보내지 않는다(기본 판정은 errorCode 있는 4xx 를 확정 거절로 읽음)
 */
public record DrawRequest(

        @NotNull(message = "은(는) 필수입니다.")
        @Min(value = 1, message = "은(는) 1 이상이어야 합니다.")
        Integer winnerCount,

        @Min(value = 1, message = "은(는) 1 이상이어야 합니다.")
        Integer expectedEntryCount
) {

    public DrawCommand toCommand() {
        return new DrawCommand(winnerCount, expectedEntryCount);
    }
}
