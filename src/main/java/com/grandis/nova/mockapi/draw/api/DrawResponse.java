package com.grandis.nova.mockapi.draw.api;

import com.grandis.nova.mockapi.draw.application.DrawState;
import com.grandis.nova.mockapi.draw.domain.DrawStatus;
import java.time.Instant;
import java.util.List;

/**
 * 회차 하나. 회차 조회와 추첨 응답이 같은 형식이다.
 *
 * <p>{@code winnerCount} · {@code drawnAt} 은 추첨 뒤에만 값이 있고, 그 전에는 생략하지 않고 null 로 나간다.
 * {@code winnerEntryIds} 는 추첨 전이면 빈 배열이다 — null 이 아니라서 worker 가 늘 배열로 읽을 수 있다.
 *
 * @param entryCount     이 회차로 받은 응모 수. worker 가 결제 완료 수({@code paidEntries})와 대조한다
 * @param winnerEntryIds 당첨 응모 id. 정확히 {@code winnerCount} 개, 겹치지 않는다. 순서는 뽑힌 순서이며 뜻은 없다
 */
public record DrawResponse(
        String drawId,
        DrawStatus status,
        long entryCount,
        Integer winnerCount,
        List<String> winnerEntryIds,
        Instant drawnAt
) {

    public static DrawResponse from(DrawState state) {
        return new DrawResponse(
                state.draw().drawId(),
                state.draw().status(),
                state.entryCount(),
                state.draw().winnerCount(),
                state.winnerEntryIds(),
                state.draw().drawnAt());
    }
}
