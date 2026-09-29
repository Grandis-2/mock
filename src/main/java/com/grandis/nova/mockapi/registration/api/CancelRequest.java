package com.grandis.nova.mockapi.registration.api;

/**
 * 예약 취소 요청. 키와 번호 중 하나 이상이 있어야 한다.
 *
 * <p>"둘 중 하나 이상" 과 길이(1~100자)는 컨트롤러가 검사한다({@code Identifiers} 참고). 컨트롤러에
 * {@code @Valid} 가 붙어 있으므로 나중에 필드를 더하며 검증 애너테이션을 달아도 그대로 먹는다.
 *
 * @param externalKey    우리 {@code preorder_token}
 * @param externalNumber Mock 이 발급한 번호
 * @param reason         {@code USER_CANCEL} · {@code GHOST_COMPENSATION} 등. <b>저장하지 않는다</b>(ERD 에 칸 없음).
 *                       그래도 명세의 필드라 선언한다 — 빠뜨리면 모르는 필드로 400 이 된다(StrictJsonConfig).
 *                       값 목록이 열려 있어("등") 열거형으로 막지 않는다
 */
public record CancelRequest(
        String externalKey,
        String externalNumber,
        String reason
) {
}
