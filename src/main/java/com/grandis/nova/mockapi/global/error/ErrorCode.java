package com.grandis.nova.mockapi.global.error;

import org.springframework.http.HttpStatus;

/**
 * Mock 이 돌려주는 오류 코드.
 *
 * <p>본 서비스는 HTTP 상태가 아니라 이 코드로 분기한다. 같은 409 라도 뜻이 다르기 때문이다.
 * 코드를 늘리기 전에 API 명세의 "오류 분류 계약" 을 먼저 고친다.
 */
public enum ErrorCode {

    /** 필수 값 누락 등 계약·설정 오류. */
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청이 올바르지 않습니다.", false),

    /** 조회 대상 없음. by-key 조회의 404 는 "지금 등록이 없다" 는 뜻이다(지연 중인 등록은 포함하지 않는다). */
    NOT_FOUND(HttpStatus.NOT_FOUND, "대상을 찾을 수 없습니다.", false),

    /** 취소 표식이 있는 키. 확정하지 않고 현재 상태로 정리한다. */
    KEY_CANCELED(HttpStatus.CONFLICT, "이미 취소된 키입니다.", true),

    /** 같은 키로 다른 내용이 왔다. 본 서비스 버그이므로 재시도하지 않는다. */
    KEY_PAYLOAD_MISMATCH(HttpStatus.UNPROCESSABLE_CONTENT, "같은 키로 다른 내용이 요청되었습니다.", false),

    /** 주입된 일시 실패. 커밋 전이라 아무것도 저장되지 않는다. */
    UPSTREAM_UNAVAILABLE(HttpStatus.INTERNAL_SERVER_ERROR, "외부 시스템을 사용할 수 없습니다.", false);

    private final HttpStatus status;
    private final String defaultMessage;

    /** 이 결과가 저장되어 같은 키의 재요청에도 같은 응답이 나오는지. */
    private final boolean replayable;

    ErrorCode(HttpStatus status, String defaultMessage, boolean replayable) {
        this.status = status;
        this.defaultMessage = defaultMessage;
        this.replayable = replayable;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }

    public boolean replayable() {
        return replayable;
    }
}
