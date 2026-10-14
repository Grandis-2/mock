package com.grandis.nova.mockapi.draw.application;

import java.util.UUID;

/**
 * 응모 내용. 둘 다 같은 응모 id 재요청의 내용 비교 대상이다 — 같으면 재생, 다르면 422.
 *
 * <p>API 의 요청 DTO 를 그대로 받지 않는 이유는 등록({@code RegisterCommand})과 같다. 의존은 api → application → domain
 * 한 방향이다.
 *
 * @param drawId     회차 id. 처음 보는 회차면 응모가 그 회차를 만든다
 * @param customerId 회원 id
 */
public record DrawEntryCommand(String drawId, UUID customerId) {
}
