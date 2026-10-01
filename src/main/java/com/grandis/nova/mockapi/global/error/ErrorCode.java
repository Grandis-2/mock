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

    /** 조회 대상의 기록 없음. by-key 조회의 404 는 "지금 등록이 없다" 는 뜻이다(지연 중인 등록은 포함하지 않는다). */
    NOT_FOUND(HttpStatus.NOT_FOUND, "대상을 찾을 수 없습니다.", false),

    /**
     * 없는 경로. 주소 · 경로 설정이 틀렸다는 뜻이라 재시도해도 같다. 워커는 재시도하지 않고 설정 오류로 알린다.
     * 기록 없음({@link #NOT_FOUND})과 같은 404 지만 코드를 나눈다 — 같으면 base-url 오타가 "등록 없음" 으로 읽힌다.
     */
    NO_SUCH_ENDPOINT(HttpStatus.NOT_FOUND, "그런 경로가 없습니다.", false),

    /** 취소 표식이 있는 키. 확정하지 않고 현재 상태로 정리한다. */
    KEY_CANCELED(HttpStatus.CONFLICT, "이미 취소된 키입니다.", true),

    /** 같은 키로 다른 내용이 왔다. 본 서비스 버그이므로 재시도하지 않는다. */
    KEY_PAYLOAD_MISMATCH(HttpStatus.UNPROCESSABLE_CONTENT, "같은 키로 다른 내용이 요청되었습니다.", false),

    /**
     * 일시 실패. 주입 실패 말고도 중복 키 재시도 상한 초과 · 처리하지 못한 오류가 이 코드로 나간다.
     * 워커에게는 모두 같은 일시 실패이고, 부하 시험은 {@code errorMessage} 로 셋을 구분한다(명세 오류 분류 계약).
     */
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
