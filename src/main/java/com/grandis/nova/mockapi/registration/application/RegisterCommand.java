package com.grandis.nova.mockapi.registration.application;

/**
 * 등록할 신청 내용. 셋 다 같은 키 재요청의 내용 비교 대상이다.
 *
 * <p>API 의 요청 DTO 를 그대로 받지 않는다. 받으면 처리 층이 HTTP 입구 층에 기대는데, 입구 층도 처리
 * 층을 부르므로 두 패키지가 서로를 가리키게 된다. 의존은 api → application → domain 한 방향이다.
 *
 * @param customerId 우리 customers.id
 * @param productId  우리 products.id
 * @param sku        우리 product_variants.sku
 */
public record RegisterCommand(Long customerId, Long productId, String sku) {
}
