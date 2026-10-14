package com.grandis.nova.mockapi.draw.application;

import com.grandis.nova.mockapi.draw.domain.Draw;
import java.util.List;

/**
 * 회차 하나의 지금 상태. 회차 조회와 추첨 응답이 같은 모양으로 쓴다.
 *
 * @param draw           회차 행
 * @param entryCount     그 회차로 받은 응모 수. 추첨 뒤에는 늘지 않는다
 * @param winnerEntryIds 당첨 응모 id, 뽑힌 순서. 추첨 전이면 비어 있다
 */
public record DrawState(Draw draw, long entryCount, List<String> winnerEntryIds) {
}
