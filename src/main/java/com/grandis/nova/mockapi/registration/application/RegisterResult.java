package com.grandis.nova.mockapi.registration.application;

import com.grandis.nova.mockapi.registration.domain.Registration;

/**
 * 등록 처리 결과.
 *
 * @param replayed      이미 있던 등록을 재생했는지. 응답 헤더 X-Idempotent-Replay 로 나간다
 * @param configVersion 이 시도가 시작할 때 얼린 설정 버전. 응답 헤더 X-Mock-Config-Version 으로 나간다
 */
public record RegisterResult(Registration registration, boolean replayed, int configVersion) {
}
