package com.grandis.nova.mockapi.draw.application;

import com.grandis.nova.mockapi.draw.domain.DrawEntry;

/**
 * 응모 처리 결과.
 *
 * @param entry         저장된 응모. 재생이면 처음 저장한 그대로다
 * @param replayed      같은 응모 id 의 재요청이었는지. 응답 헤더 {@code X-Idempotent-Replay} 로 나간다
 * @param configVersion 이 시도가 쓴 설정의 버전. 응답 헤더 {@code X-Mock-Config-Version} 으로 나간다
 */
public record DrawEntryResult(DrawEntry entry, boolean replayed, int configVersion) {
}
