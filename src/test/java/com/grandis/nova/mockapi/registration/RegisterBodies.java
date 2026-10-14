package com.grandis.nova.mockapi.registration;

/**
 * 시험용 등록 요청 본문. 본 서비스(preorder)의 {@code RegisterRequestPayload} 모양 그대로다.
 *
 * <p>본문의 {@code ourReservationId} 는 {@code Idempotency-Key} 헤더와 같아야 하므로(다르면 400) 키마다 본문이
 * 달라진다. 그래서 상수 하나로 두지 않고 키를 받아 만든다.
 */
public final class RegisterBodies {

    /** 본 서비스가 보내는 모양 그대로 — {@code UUID.toString()}(소문자 표준 표기). */
    public static final String CUSTOMER_REF = "0199a3f2-7c4e-7a10-8b2d-3f4e5a6b7c8d";
    public static final String ITEM_CODE = "0199a3f2-7c4e-7b20-9c3e-4f5a6b7c8d9e";
    public static final String OPTION_CODE = "SM-G999-256-BLK";
    public static final String SCOPE = "preorder";

    private RegisterBodies() {
    }

    /** 기본 신청 내용으로 {@code key} 의 본문을 만든다. */
    public static String of(String key) {
        return of(key, CUSTOMER_REF, OPTION_CODE);
    }

    /** 고객 참조와 옵션 코드만 바꾼 본문. 같은 키 · 다른 내용(422) 시험에 쓴다. */
    public static String of(String key, String customerRef, String optionCode) {
        return """
                {"ourReservationId":"%s","customerRef":"%s","itemCode":"%s","optionCode":"%s","qty":1,"scope":"%s"}"""
                .formatted(key, customerRef, ITEM_CODE, optionCode, SCOPE);
    }
}
