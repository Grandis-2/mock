package com.grandis.nova.mockapi.registration.api;

import com.grandis.nova.mockapi.registration.application.RegisterCommand;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * 예약 등록 요청. 본 서비스(preorder)가 보내는 본문({@code RegisterRequestPayload}) 그대로다.
 *
 * <p><b>요청은 preorder 형식, 원장은 ERD 형식이다.</b> be 결정(2026-10-06)으로 요청 이름 · 타입은 preorder 가 실제로
 * 보내는 값을 따르고, 원장 칸({@code customer_id} · {@code product_id} · {@code sku})에는 {@link #toCommand()} 가
 * 바꿔 넣는다. 이름 · 타입 · 개수를 바꾸면 본 서비스의 요청이 전부 400 이 되므로 저쪽과 함께 바꾼다.
 *
 * <p><b>참조 · 코드는 소문자 표준 UUID(8-4-4-4-12)만 받는다.</b> 본 서비스의 id 가 UUID 라(be NV-326)
 * {@code UUID.toString()} 그대로 온다. 다른 표기가 온다면 보낸 쪽의 버그다 — 정규화해 받으면 그 버그가 조용히 같은 신청으로
 * 묻히므로, 바꾸지 않고 400 으로 드러낸다. 이 형식을 지나면
 * {@code UUID.fromString} 이 실패하지 않고, 응답의 {@code toString()} 도 보낸 글자와 같다.
 * 빈 값도 형식 검사가 막으므로 필수 검사는 {@code @NotNull} 로 둔다 — {@code @NotBlank} 를 더하면 빈 값에 문장이 둘 나온다.
 *
 * <p>{@code qty} · {@code scope} 는 저장하지 않는다(ERD 에 칸 없음). 사전예약은 수량 1 고정이라 {@code qty} 는 1 만 받고,
 * 그 밖은 같은 키 · 다른 내용(422)이 아니라 잘못된 요청(400)이다. {@code scope} 는 비교하지 않으므로 같은 키로
 * {@code scope} 만 달라도 재생된다.
 *
 * <p>래퍼형이다. 기본형이면 {@code qty} 를 빼먹었을 때 0 이 들어가 필수 검사를 지나친다.
 * 명세에 없는 필드는 받지 않는다 — 모르는 필드는 400 이다(StrictJsonConfig).
 *
 * @param ourReservationId 본 서비스 preorder_token. {@code Idempotency-Key} 헤더와 같아야 한다(컨트롤러가 확인)
 * @param customerRef      고객 참조. customers.id(UUID) 문자열 → 원장 {@code customer_id}
 * @param itemCode         상품 코드. products.id(UUID) 문자열 → 원장 {@code product_id}
 * @param optionCode       옵션 코드. product_options.sku → 원장 {@code sku}
 * @param qty              수량. 1 만 받는다. 저장하지 않는다
 * @param scope            요청 범위. 지금은 preorder. 저장 · 비교하지 않는다
 */
public record RegisterRequest(

        @NotBlank(message = "은(는) 필수입니다.")
        String ourReservationId,

        @NotNull(message = "은(는) 필수입니다.")
        @Pattern(regexp = ID_PATTERN, message = ID_MESSAGE)
        String customerRef,

        @NotNull(message = "은(는) 필수입니다.")
        @Pattern(regexp = ID_PATTERN, message = ID_MESSAGE)
        String itemCode,

        @NotBlank(message = "은(는) 필수입니다.")
        @Size(max = 80, message = "은(는) 80자 이하여야 합니다.")
        String optionCode,

        @NotNull(message = "은(는) 필수입니다.")
        @Min(value = 1, message = QTY_MESSAGE)
        @Max(value = 1, message = QTY_MESSAGE)
        Integer qty,

        @NotBlank(message = "은(는) 필수입니다.")
        @Size(max = 50, message = "은(는) 50자 이하여야 합니다.")
        String scope
) {

    /** 소문자 표준 UUID. {@code UUID.toString()} 의 모양이다. */
    static final String ID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    static final String ID_MESSAGE = "은(는) 소문자 표준 UUID(8-4-4-4-12)여야 합니다.";

    static final String QTY_MESSAGE = "은(는) 1 이어야 합니다.";

    /**
     * 검증을 통과한 요청을 원장 칸의 신청 내용으로 바꾼다. 형식 검사를 지났으므로 UUID 변환은 실패하지 않는다.
     * {@code ourReservationId} 는 키와 같고, {@code qty} · {@code scope} 는 저장하지 않아 담지 않는다.
     */
    public RegisterCommand toCommand() {
        return new RegisterCommand(UUID.fromString(customerRef), UUID.fromString(itemCode), optionCode);
    }
}
