package com.grandis.nova.mockapi.registration.api;

/**
 * 예약 취소 요청. 키와 번호 중 하나 이상이 있어야 한다. 본 서비스(preorder)가 보내는 본문
 * ({@code CancelRequestPayload}) 그대로다.
 *
 * <p>"둘 중 하나 이상" 과 형식(영문 · 숫자 · {@code . _ -} 1~100자)은 컨트롤러가 검사한다({@code Identifiers} 참고). 컨트롤러에
 * {@code @Valid} 가 붙어 있으므로 나중에 필드를 더하며 검증 애너테이션을 달아도 그대로 먹는다.
 *
 * @param externalKey   우리 {@code preorder_token}
 * @param reservationNo Mock 이 발급한 예약번호(응답의 {@code externalNumber}). 등록이 확인되지 않았으면 null
 * @param reason        {@code USER_CANCEL} · {@code ADMIN_CANCEL} · {@code DEADLINE_EXCEEDED} · {@code GHOST_COMPENSATION} 등. <b>저장하지 않는다</b>(ERD 에 칸 없음).
 *                      그래도 명세의 필드라 선언한다 — 빠뜨리면 모르는 필드로 400 이 된다(StrictJsonConfig).
 *                      값 목록이 열려 있어("등") 열거형으로 막지 않는다
 */
public record CancelRequest(
        String externalKey,
        String reservationNo,
        String reason
) {
}
