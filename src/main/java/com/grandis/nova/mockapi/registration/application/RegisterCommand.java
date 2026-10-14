package com.grandis.nova.mockapi.registration.application;

import java.util.UUID;

/**
 * 등록할 신청 내용. 셋 다 같은 키 재요청의 내용 비교 대상이다.
 *
 * <p>원장 칸 형식이다. 칸 이름은 ERD v14.1 을 따르고, id 두 칸만 be 의 UUID 전환(NV-326)을 따라 ERD 의 정수와 달리
 * UUID 다. 요청은 preorder 형식이라 입구({@code RegisterRequest#toCommand})가 바꿔 만든다.
 *
 * <p>API 의 요청 DTO 를 그대로 받지 않는다. 받으면 처리 층이 HTTP 입구 층에 기대는데, 입구 층도 처리
 * 층을 부르므로 두 패키지가 서로를 가리키게 된다. 의존은 api → application → domain 한 방향이다.
 *
 * @param customerId 우리 customers.id
 * @param productId  우리 products.id
 * @param sku        우리 product_options.sku
 */
public record RegisterCommand(UUID customerId, UUID productId, String sku) {
}
