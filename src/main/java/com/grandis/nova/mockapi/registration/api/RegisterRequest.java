package com.grandis.nova.mockapi.registration.api;

import com.grandis.nova.mockapi.registration.application.RegisterCommand;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 예약 등록 요청. 본 서비스(preorder)가 보내는 본문({@code RegisterRequestPayload}) 그대로다.
 *
 * <p>be 결정(2026-10-06)으로 ERD 가 아니라 preorder 가 실제로 보내는 값을 따른다. 이름 · 타입 · 개수를 바꾸면
 * 본 서비스의 요청이 전부 400 이 되므로 저쪽과 함께 바꾼다.
 *
 * <p>래퍼형이다. 기본형이면 {@code qty} 를 빼먹었을 때 0 이 들어가 필수 검사를 지나친다.
 * 명세에 없는 필드는 받지 않는다 — 모르는 필드는 400 이다(StrictJsonConfig).
 *
 * @param ourReservationId 본 서비스 preorder_token. {@code Idempotency-Key} 헤더와 같아야 한다(컨트롤러가 확인)
 * @param customerRef      고객 참조. 지금은 customers.id 를 문자열로 보낸다. 숫자로 바꾸지 않는다
 * @param itemCode         상품 코드. 지금은 products.id 문자열
 * @param optionCode       옵션 코드. product_options.sku
 * @param qty              수량. 지금은 항상 1
 * @param scope            요청 범위. 지금은 preorder
 */
public record RegisterRequest(

        @NotBlank(message = "은(는) 필수입니다.")
        String ourReservationId,

        @NotBlank(message = "은(는) 필수입니다.")
        @Size(max = 100, message = "은(는) 100자 이하여야 합니다.")
        String customerRef,

        @NotBlank(message = "은(는) 필수입니다.")
        @Size(max = 100, message = "은(는) 100자 이하여야 합니다.")
        String itemCode,

        @NotBlank(message = "은(는) 필수입니다.")
        @Size(max = 80, message = "은(는) 80자 이하여야 합니다.")
        String optionCode,

        @NotNull(message = "은(는) 필수입니다.")
        @Min(value = 1, message = "은(는) 1 이상이어야 합니다.")
        Integer qty,

        @NotBlank(message = "은(는) 필수입니다.")
        @Size(max = 50, message = "은(는) 50자 이하여야 합니다.")
        String scope
) {

    /** 검증을 통과한 요청을 처리 층의 신청 내용으로 바꾼다. {@code ourReservationId} 는 키와 같아 담지 않는다. */
    public RegisterCommand toCommand() {
        return new RegisterCommand(customerRef, itemCode, optionCode, qty, scope);
    }
}
