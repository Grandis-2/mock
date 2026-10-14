package com.grandis.nova.mockapi.draw.api;

import com.grandis.nova.mockapi.draw.domain.DrawEntry;
import java.time.Instant;
import java.util.UUID;

/**
 * 응모 한 건. 새 응모와 재생이 같은 형식이고, 재생은 처음 저장한 값을 그대로 준다(헤더 {@code X-Idempotent-Replay} 로 가른다).
 *
 * <p>당첨 여부는 싣지 않는다 — 결과는 회차 조회 · 추첨 응답의 {@code winnerEntryIds} 로 준다. 시각은 {@link Instant} 라 끝에
 * {@code Z} 가 붙고 밀리초 세 자리로 나간다(JsonTimeConfig).
 */
public record DrawEntryResponse(
        String entryId,
        String drawId,
        UUID customerId,
        Instant receivedAt
) {

    public static DrawEntryResponse from(DrawEntry entry) {
        return new DrawEntryResponse(entry.entryId(), entry.drawId(), entry.customerId(), entry.receivedAt());
    }
}
