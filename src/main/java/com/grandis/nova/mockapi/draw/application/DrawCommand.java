package com.grandis.nova.mockapi.draw.application;

/**
 * 추첨 요청. 둘 다 같은 회차의 추첨 재요청의 내용 비교 대상이다 — 같으면 재생, 다르면 422.
 *
 * @param winnerCount        당첨 인원(be {@code DRAW_REQUESTED.payload.winnerCount})
 * @param expectedEntryCount 기대 응모 수(be {@code payload.paidEntries}). null 이면 수를 확인하지 않는다
 */
public record DrawCommand(int winnerCount, Integer expectedEntryCount) {
}
