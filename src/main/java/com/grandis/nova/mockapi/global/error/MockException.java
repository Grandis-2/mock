package com.grandis.nova.mockapi.global.error;

/**
 * Mock 이 의도적으로 내는 오류. {@link GlobalExceptionHandler} 가 응답 형식으로 바꾼다.
 */
public class MockException extends RuntimeException {

    private final ErrorCode errorCode;

    /** 그 키에 이미 번호가 있으면 채운다. 감사용이라 없으면 null 이다. */
    private final String externalNumber;

    public MockException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage(), null);
    }

    public MockException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public MockException(ErrorCode errorCode, String message, String externalNumber) {
        super(message);
        this.errorCode = errorCode;
        this.externalNumber = externalNumber;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public String externalNumber() {
        return externalNumber;
    }
}
