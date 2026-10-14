package com.grandis.nova.mockapi.draw.api;

import com.grandis.nova.mockapi.draw.application.DrawEntryCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

/**
 * 럭키드로우 응모 요청. be worker 가 응모비 결제 완료(DRAW_ENTRY_PAID) 한 건마다 보낸다(be {@code contracts/draw-entry-sync.md}).
 *
 * <p>{@code entryId} · {@code drawId} 의 형식(영문 · 숫자 · {@code . _ -} 1~100자)은 컨트롤러가 {@code Identifiers} 로 본다 —
 * 등록 키와 같은 규칙을 한 곳에서 맞추고, {@code drawId} 는 회차 조회 · 추첨의 경로에도 들어가기 때문이다. 여기서는 빠졌는지만
 * 본다.
 *
 * <p>{@code customerId} 는 등록의 {@code customerRef} 와 같은 규칙이다 — 소문자 표준 UUID 만 받는다. 본 서비스의 회원 id 가
 * UUID 라 {@code UUID.toString()} 그대로 오고, 다른 표기는 보낸 쪽 버그라 정규화하지 않고 400 으로 드러낸다.
 * 명세에 없는 필드는 받지 않는다 — 모르는 필드는 400 이다(StrictJsonConfig).
 *
 * @param entryId    응모 id(be 봉투의 aggregateId). {@code Idempotency-Key} 헤더와 같아야 한다(컨트롤러가 확인)
 * @param drawId     회차 id(payload.drawId)
 * @param customerId 회원 id(payload.customerId)
 */
public record DrawEntryRequest(

        @NotBlank(message = "은(는) 필수입니다.")
        String entryId,

        @NotBlank(message = "은(는) 필수입니다.")
        String drawId,

        @NotNull(message = "은(는) 필수입니다.")
        @Pattern(regexp = UUID_PATTERN, message = UUID_MESSAGE)
        String customerId
) {

    /** 소문자 표준 UUID. 등록 요청({@code RegisterRequest})의 참조 · 코드와 같은 모양이다. */
    static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    static final String UUID_MESSAGE = "은(는) 소문자 표준 UUID(8-4-4-4-12)여야 합니다.";

    /** 검증을 통과한 요청을 응모 내용으로 바꾼다. {@code entryId} 는 키라 따로 넘긴다. 형식 검사를 지났으므로 UUID 변환은 실패하지 않는다. */
    public DrawEntryCommand toCommand() {
        return new DrawEntryCommand(drawId, UUID.fromString(customerId));
    }
}
