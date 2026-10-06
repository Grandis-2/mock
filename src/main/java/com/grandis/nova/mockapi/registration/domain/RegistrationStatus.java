package com.grandis.nova.mockapi.registration.domain;

/**
 * 등록 행의 상태. DB 의 {@code ck_registration_status} 가 이 둘만 허용한다.
 *
 * <p>업무 거절(REJECTED)은 두지 않는다. Mock 은 영구 실패를 만들지 않기로 했다.
 */
public enum RegistrationStatus {

    /** 등록돼 살아 있다. 번호·회원·상품·sku·확정 시각이 모두 채워져 있어야 한다. */
    ACTIVE,

    /**
     * 취소됐다. 두 가지 경우가 있다.
     * <ul>
     *   <li>등록 후 취소 — 등록 정보는 그대로 두고 취소 시각만 채운다</li>
     *   <li>등록 전 취소(취소 표식) — 키·상태·취소 시각만 있고 번호는 null 이다</li>
     * </ul>
     * 취소된 행은 다시 활성화하지 않는다.
     */
    CANCELED
}
