package com.grandis.nova.mockapi.registration;

import com.grandis.nova.mockapi.global.error.ErrorCode;
import com.grandis.nova.mockapi.global.error.MockException;

/**
 * 외부 키 · 외부 번호의 길이 규칙. ERD 의 두 칸이 {@code varchar(100)} 이라 1~100자다.
 *
 * <p>애너테이션이 아니라 이걸로 검사한다. 헤더 · 경로 변수에 검증 애너테이션을 붙이면 스프링이 본문
 * 검증까지 메서드 검증으로 바꿔, 오류 메시지에 필드 이름 대신 매개변수 이름이 나간다.
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
