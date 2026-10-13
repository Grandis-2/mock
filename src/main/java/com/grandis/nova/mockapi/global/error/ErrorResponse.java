package com.grandis.nova.mockapi.global.error;

/**
 * Mock 의 실패 응답 형식.
 *
 * <p>본 서비스의 공통 응답 봉투(success/data/error)를 쓰지 않는다. Mock 은 남의 회사 시스템을
 * 연기하는 역할이라 우리 포맷을 따르지 않는 편이 현실적이다.
 *
 * <p>{@code externalNumber} 는 값이 없어도 필드를 생략하지 않고 null 로 내보낸다.
 */
public record ErrorResponse(
        String errorCode,
        String errorMessage,
        boolean replayable,
        String externalNumber
) {

    public static ErrorResponse of(ErrorCode code) {
        return new ErrorResponse(code.name(), code.defaultMessage(), code.replayable(), null);
    }

    public static ErrorResponse of(ErrorCode code, String message) {
        return new ErrorResponse(code.name(), message, code.replayable(), null);
    }

    public static ErrorResponse of(ErrorCode code, String message, String externalNumber) {
        return new ErrorResponse(code.name(), message, code.replayable(), externalNumber);
    }
}
