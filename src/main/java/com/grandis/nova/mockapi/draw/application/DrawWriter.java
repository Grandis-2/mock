package com.grandis.nova.mockapi.draw.application;

import com.grandis.nova.mockapi.draw.domain.Draw;
import com.grandis.nova.mockapi.draw.domain.DrawEntry;
import com.grandis.nova.mockapi.draw.domain.DrawEntryRepository;
import com.grandis.nova.mockapi.draw.domain.DrawRepository;
import com.grandis.nova.mockapi.draw.domain.WinnerPicker;
import com.grandis.nova.mockapi.global.error.ErrorCode;
import com.grandis.nova.mockapi.global.error.MockException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 추첨 처리 중 트랜잭션 안의 부분.
 *
 * <p>회차 행을 잠근 채 세고 뽑는다. 응모도 같은 행을 먼저 잠그므로, 센 뒤에 응모가 끼어들어 "센 수와 뽑은 대상이 다른" 일이
 * 없다 — 기대 응모 수({@code expectedEntryCount})를 추첨 요청에 싣는 이유다(조회와 추첨 사이에 응모가 닿아도 어긋나지 않음).
 *
 * <p>응모와 달리 새 행을 만들지 않으므로 중복 키가 나지 않아 재시도 루프가 없다. 잠금 순서는 응모와 같다(회차 행 → 응모 행).
 * 격리 수준은 응모와 같은 이유로 READ COMMITTED 다 — 없는 회차를 잠그는 읽기가 갭 락을 걸지 않게.
 */
@Component
public class DrawWriter {

    private final DrawRepository draws;
    private final DrawEntryRepository entries;
    private final WinnerPicker picker;

    public DrawWriter(DrawRepository draws, DrawEntryRepository entries, WinnerPicker picker) {
        this.draws = draws;
        this.entries = entries;
        this.picker = picker;
    }

    /**
     * @throws MockException 받은 응모가 없는 회차(404) · 기대 수와 다름(409) · 다른 추첨 요청(422) · 당첨 인원이 응모 수 이상(422)
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Attempt drawOnce(String drawId, DrawCommand command) {
        Draw draw = draws.findByIdForUpdate(drawId).orElse(null);
        if (draw == null) {
            // 처음 보는 회차는 받은 수 0 이다. 기대 수를 보냈으면 수가 다른 것이고(늦춰 다시), 안 보냈으면 조회와 같은 404 다.
            // 회차를 만들지 않는다 — 회차는 응모가 만든다.
            if (command.expectedEntryCount() != null) {
                throw countMismatch(command.expectedEntryCount(), 0);
            }
            throw new MockException(ErrorCode.NOT_FOUND, "이 회차로 받은 응모가 아직 없습니다. drawId=" + drawId);
        }

        // 회차 id 가 멱등 키다. 이미 추첨했으면 다시 뽑지 않는다 — 같은 요청을 두 번 받아도 추첨은 한 번이다(be 계약).
        if (draw.isDrawn()) {
            return replay(draw, command);
        }

        List<String> candidates = entries.findEntryIds(drawId);
        if (command.expectedEntryCount() != null && command.expectedEntryCount() != candidates.size()) {
            throw countMismatch(command.expectedEntryCount(), candidates.size());
        }
        if (command.winnerCount() >= candidates.size()) {
            throw new MockException(ErrorCode.INVALID_WINNER_COUNT,
                    "당첨 인원(" + command.winnerCount() + ")은 받은 응모 수(" + candidates.size() + ")보다 적어야 합니다.");
        }

        List<String> winners = picker.pick(candidates, command.winnerCount());
        Map<String, DrawEntry> byId = entries.findAllById(winners).stream()
                .collect(Collectors.toMap(DrawEntry::entryId, Function.identity()));
        for (int i = 0; i < winners.size(); i++) {
            byId.get(winners.get(i)).markWinner(i + 1);
        }
        // 밀리초로 자른다. DB 에 저장된 값과 첫 응답의 값이 같아야 재생 응답과 어긋나지 않는다.
        draw.markDrawn(command.winnerCount(), command.expectedEntryCount(),
                Instant.now().truncatedTo(ChronoUnit.MILLIS));
        return new Attempt(new DrawState(draw, candidates.size(), winners), false);
    }

    private Attempt replay(Draw draw, DrawCommand command) {
        List<String> differing = draw.differingFields(command.winnerCount(), command.expectedEntryCount());
        if (!differing.isEmpty()) {
            throw new MockException(ErrorCode.KEY_PAYLOAD_MISMATCH,
                    "이미 추첨한 회차에 다른 추첨이 요청되었습니다. " + String.join(", ", differing) + " 이(가) 다릅니다.");
        }
        String drawId = draw.drawId();
        return new Attempt(
                new DrawState(draw, entries.countByDrawId(drawId), entries.findWinnerEntryIds(drawId)), true);
    }

    private static MockException countMismatch(int expected, int received) {
        return new MockException(ErrorCode.ENTRY_COUNT_MISMATCH,
                "받은 응모 수가 기대 수와 다릅니다. 받은 수=" + received + ", 기대 수=" + expected);
    }

    /** 한 번의 시도 결과. */
    public record Attempt(DrawState state, boolean replayed) {
    }
}
