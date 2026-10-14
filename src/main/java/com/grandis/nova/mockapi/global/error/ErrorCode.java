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
     * 럭키드로우 — 이미 추첨한 회차에 새 응모가 왔다. 저장하지 않는다. 추첨이 저장돼 있어 같은 요청은 늘 같은 응답이다.
     * 워커는 DLQ 로 보내고, order 의 「드로우 추첨 뒤 결제 완료」 ERROR 와 함께 사람이 정리한다. 이미 받은 응모를 다시
     * 보낸 것은 추첨 뒤여도 재생이라 이 코드가 나가지 않는다.
     */
    DRAW_CLOSED(HttpStatus.CONFLICT, "이미 추첨한 회차입니다.", true),

    /**
     * 럭키드로우 — 같은 회차에 같은 회원의 다른 응모가 이미 있다. 저장하지 않는다. be 가 회차당 회원 하나를 보장하므로
     * be 버그일 때만 생긴다 — 조용히 받으면 한 회원이 두 번 추첨에 들어간다. 먼저 받은 응모가 저장돼 있어 같은 요청은
     * 늘 같은 응답이다.
     */
    CUSTOMER_ALREADY_ENTERED(HttpStatus.CONFLICT, "같은 회차에 같은 회원의 응모가 이미 있습니다.", true),

    /**
     * 럭키드로우 — 추첨 요청의 기대 응모 수({@code expectedEntryCount})가 받은 응모 수와 다르다. 아무것도 바꾸지 않는다.
     * 응모가 더 닿으면 달라지므로 재생 결과가 아니다. 워커는 "늦춰 다시"(가시성 지연)로 읽어야 하는데, 기본 판정은 errorCode 가
     * 있는 4xx 를 확정 거절로 읽으므로 그 처리를 넣은 뒤에만 기대 수를 보낸다(기대 수는 선택 칸).
     */
    ENTRY_COUNT_MISMATCH(HttpStatus.CONFLICT, "받은 응모 수가 기대 수와 다릅니다.", false),

    /** 럭키드로우 — 당첨 인원이 받은 응모 수 이상이다. be 는 전원 당첨이면 Mock 을 부르지 않으므로 be 버그다. */
    INVALID_WINNER_COUNT(HttpStatus.UNPROCESSABLE_CONTENT, "당첨 인원은 받은 응모 수보다 적어야 합니다.", false),

    /**
     * 진행 중인 등록 · 취소가 있어 초기화를 거절했다. 그대로 지우면 지연 중이던 등록이 초기화 뒤에 커밋되어,
     * 취소한 키가 ACTIVE 로 살아난다. 제어 API 의 응답이라 워커는 받지 않는다. 끝난 뒤 다시 부르면 된다.
     */
    RESET_BUSY(HttpStatus.CONFLICT, "진행 중인 등록 · 취소가 있어 초기화할 수 없습니다.", false),

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
