package com.grandis.nova.mockapi.registration.application;

/**
 * 등록할 신청 내용. 다섯 칸 모두 같은 키 재요청의 내용 비교 대상이다.
 *
 * <p>API 의 요청 DTO 를 그대로 받지 않는다. 받으면 처리 층이 HTTP 입구 층에 기대는데, 입구 층도 처리
 * 층을 부르므로 두 패키지가 서로를 가리키게 된다. 의존은 api → application → domain 한 방향이다.
 *
 * <p>요청 본문의 {@code ourReservationId} 는 담지 않는다. 키({@code Idempotency-Key})와 같은 값이라
 * 입구에서 같은지만 확인하고 버린다.
 *
 * @param customerRef 고객 참조. 지금은 본 서비스 customers.id 문자열
 * @param itemCode    상품 코드. 지금은 products.id 문자열
 * @param optionCode  옵션 코드. product_options.sku
 * @param qty         수량. 지금은 항상 1
 * @param scope       요청 범위. 지금은 preorder
 */
public record RegisterCommand(String customerRef, String itemCode, String optionCode, int qty, String scope) {
}
