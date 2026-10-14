package com.grandis.nova.mockapi.draw.application;

import com.grandis.nova.mockapi.draw.domain.Draw;
import com.grandis.nova.mockapi.draw.domain.DrawEntryRepository;
import com.grandis.nova.mockapi.draw.domain.DrawRepository;
import com.grandis.nova.mockapi.global.error.ErrorCode;
import com.grandis.nova.mockapi.global.error.MockException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회차 조회. worker 가 추첨 전에 Mock 이 받은 응모 수를 결제 완료 수와 대조할 때 부른다.
 *
 * <p>지연 · 실패를 넣지 않는다 — 조회에는 넣지 않는 기존 규칙(등록 목록 · 키 조회)과 같다.
 *
 * <p><b>REPEATABLE READ 로 읽는다.</b> 회차 · 응모 수 · 당첨자를 문장 셋으로 읽는데, READ COMMITTED 면 문장마다 그때의 커밋을
 * 봐서 그 사이에 추첨이 커밋되면 "OPEN 인데 당첨자가 있는" 응답이 나간다. REPEATABLE READ 는 첫 읽기의 사진 한 장으로 끝까지
 * 읽어 셋이 어긋나지 않고, 잠그지 않으므로 응모 · 추첨을 막지도 않는다. 잠금 읽기를 하지 않으니 갭 락도 없다.
 * 진행 중(커밋 전)인 응모는 세지 않는다 — worker 는 수가 다르면 기다렸다 다시 대조한다(be 계약).
 */
@Component
public class DrawReader {

    private final DrawRepository draws;
    private final DrawEntryRepository entries;

    public DrawReader(DrawRepository draws, DrawEntryRepository entries) {
        this.draws = draws;
        this.entries = entries;
    }

    /** @throws MockException 받은 응모가 아직 없는 회차(404) — worker 는 응모 수 0 으로 읽으면 된다 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DrawState find(String drawId) {
        Draw draw = draws.findById(drawId).orElseThrow(() -> new MockException(ErrorCode.NOT_FOUND,
                "이 회차로 받은 응모가 아직 없습니다. drawId=" + drawId));
        return new DrawState(draw, entries.countByDrawId(drawId), entries.findWinnerEntryIds(drawId));
    }
}
