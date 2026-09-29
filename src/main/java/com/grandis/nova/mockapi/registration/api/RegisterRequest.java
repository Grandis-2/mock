package com.grandis.nova.mockapi.registration.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 예약 등록 요청. 셋 다 같은 키 재요청의 내용 비교 대상이다.
 *
 * <p>래퍼형이다. 기본형이면 필드를 빼먹었을 때 0 이 들어가 "회원 0번" 으로 저장된다.
 * 명세에 없는 필드는 받지 않는다 — 모르는 필드는 400 이다(StrictJsonConfig).
 *
 * @param customerId 우리 customers.id
 * @param productId  우리 products.id
 * @param sku        우리 product_variants.sku
 */
public record RegisterRequest(

        @NotNull(message = "은(는) 필수입니다.")
        Long customerId,

        @NotNull(message = "은(는) 필수입니다.")
        Long productId,

        @NotBlank(message = "은(는) 필수입니다.")
        @Size(max = 80, message = "은(는) 80자 이하여야 합니다.")
        String sku
) {
}
