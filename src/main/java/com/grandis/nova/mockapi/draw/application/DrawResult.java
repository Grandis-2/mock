package com.grandis.nova.mockapi.draw.application;

/**
 * 추첨 처리 결과.
 *
 * @param state         추첨한 회차의 상태. 재생이면 처음 추첨한 그대로다
 * @param replayed      같은 회차의 추첨 재요청이었는지. 응답 헤더 {@code X-Idempotent-Replay} 로 나간다
 * @param configVersion 이 시도가 쓴 설정의 버전. 응답 헤더 {@code X-Mock-Config-Version} 으로 나간다
 */
public record DrawResult(DrawState state, boolean replayed, int configVersion) {
}
