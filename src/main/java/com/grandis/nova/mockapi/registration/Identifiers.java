package com.grandis.nova.mockapi.registration;

import com.grandis.nova.mockapi.global.error.ErrorCode;
import com.grandis.nova.mockapi.global.error.MockException;

/**
 * 외부 키 · 외부 번호의 길이 규칙. ERD 의 두 칸이 {@code varchar(100)} 이라 1~100자다.
 *
 * <p>쓰는 곳마다 애너테이션 대신 이걸 쓰는 이유가 다르다.
 * <ul>
 *   <li>등록의 {@code Idempotency-Key} 헤더 · 키 조회의 경로 변수 — 헤더 · 경로 변수에 검증 애너테이션을
 *       붙이면 스프링이 본문 검증까지 메서드 검증으로 바꿔, 오류 메시지에 필드 이름 대신 매개변수 이름이 나간다</li>
 *   <li>취소 본문 — 본문 record 는 애너테이션으로도 필드 이름이 제대로 나온다. 다만 "키와 번호 중 하나 이상"
 *       은 애너테이션으로 쓸 수 없어 어차피 컨트롤러에서 검사하고, 길이 규칙도 등록 키와 한 곳에서 맞춘다</li>
 * </ul>
 */
final class Identifiers {

    static final int MAX_LENGTH = 100;

    private Identifiers() {
    }

    /**
     * @param name 오류 메시지에 쓸 이름. API 의 필드 · 헤더 이름이다
     * @throws MockException 비었거나 100자를 넘으면 400
     */
    static void requireLength(String name, String value) {
        if (value.isBlank() || value.length() > MAX_LENGTH) {
            throw new MockException(ErrorCode.INVALID_REQUEST,
                    name + " 은(는) 1~" + MAX_LENGTH + "자여야 합니다.");
        }
    }
}
