package com.grandis.nova.mockapi.registration.api;

import com.grandis.nova.mockapi.registration.application.RegistrationPage;
import java.util.List;

/**
 * 목록 조회 응답. {@code items} 한 건은 등록 응답 · 번호 조회 응답과 같은 형식이다.
 *
 * <p>{@code nextCursor} 는 더 없으면 {@code null} 로 내보낸다. 필드를 생략하지 않는다 — 받는 쪽은 이 값이 null 일
 * 때까지 부르면 끝까지 받은 것이다.
 *
 * @param items      외부 키 순서. 등록 행과 취소 표식 행(번호 · 회원 · 상품 · sku 가 null)이 섞여 있다
 * @param nextCursor 다음 요청의 {@code cursor}. 마지막 페이지면 null
 */
public record RegistrationPageResponse(List<RegistrationResponse> items, String nextCursor) {

    public static RegistrationPageResponse from(RegistrationPage page) {
        return new RegistrationPageResponse(
                page.items().stream().map(RegistrationResponse::from).toList(),
                page.nextCursor());
    }
}
