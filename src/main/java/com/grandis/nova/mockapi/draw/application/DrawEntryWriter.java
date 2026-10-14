package com.grandis.nova.mockapi.draw.application;

import com.grandis.nova.mockapi.draw.domain.Draw;
import com.grandis.nova.mockapi.draw.domain.DrawEntry;
import com.grandis.nova.mockapi.draw.domain.DrawEntryRepository;
import com.grandis.nova.mockapi.draw.domain.DrawRepository;
import com.grandis.nova.mockapi.global.error.ErrorCode;
import com.grandis.nova.mockapi.global.error.MockException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 응모 처리 중 트랜잭션 안의 부분. 한 번 호출이 한 번의 시도다.
 *
 * <p>등록({@code RegistrationWriter})과 같은 이유로 서비스와 나눈다 — 중복 키가 나면 그 트랜잭션은 롤백 전용이 되어
 * 트랜잭션을 나와 새로 시작해야 하므로, 재시도 루프는 바깥({@link DrawEntryService})에 두고 한 번의 시도는 여기에 둔다.
 *
 * <p><b>격리 수준은 여기서 정한다</b>(등록과 같다). 없는 회차를 {@code FOR UPDATE} 로 읽으면 REPEATABLE READ 에서는 갭 락이
 * 걸려, 처음 보는 회차 둘로 동시에 온 응모의 INSERT 가 서로의 갭 락에 막혀 교착(1213)이 난다.
 */
@Component
public class DrawEntryWriter {

    private final DrawRepository draws;
    private final DrawEntryRepository entries;

    public DrawEntryWriter(DrawRepository draws, DrawEntryRepository entries) {
        this.draws = draws;
        this.entries = entries;
    }

    /**
     * @throws MockException 같은 응모 id 다른 내용(422) · 추첨한 회차(409) · 같은 회원의 다른 응모(409)
     * @throws org.springframework.dao.DataIntegrityViolationException 중복 키. 바깥에서 새 트랜잭션으로 다시 부른다
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Attempt enterOnce(String entryId, DrawEntryCommand command) {
        // 밀리초로 자른다. DB 에 저장된 값과 첫 응답의 값이 같아야 재생 응답과 어긋나지 않는다.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        // 회차 행을 잠근다. 없으면 만든다 — Mock 은 회차를 미리 모르고, 거절하면 응모가 전부 DLQ 로 간다(be 계약).
        // 이 뒤로 같은 회차의 응모 · 추첨은 이 트랜잭션이 끝날 때까지 기다린다.
        Draw draw = draws.findByIdForUpdate(command.drawId()).orElseGet(() -> open(command.drawId(), now));

        // 같은 응모 id 가 먼저다. 추첨 뒤에 온 재전송도 재생이어야 한다 — 이미 받은 응모를 다시 보낸 것이지 새 응모가 아니다.
        Optional<DrawEntry> existing = entries.findById(entryId);
        if (existing.isPresent()) {
            return judgeExisting(existing.get(), command);
        }

        if (draw.isDrawn()) {
            throw new MockException(ErrorCode.DRAW_CLOSED,
                    "이미 추첨한 회차입니다. drawId=" + command.drawId());
        }
        if (entries.existsByDrawIdAndCustomerId(command.drawId(), command.customerId())) {
            throw new MockException(ErrorCode.CUSTOMER_ALREADY_ENTERED,
                    "같은 회차에 같은 회원의 응모가 이미 있습니다. drawId=" + command.drawId());
        }

        DrawEntry created = DrawEntry.received(entryId, command.drawId(), command.customerId(), now);
        // 여기서 INSERT 를 내보낸다. 중복 키가 커밋 시점이 아니라 이 줄에서 나야 원인이 분명하다.
        entries.saveAndFlush(created);
        return new Attempt(created, false);
    }

    /** 처음 보는 회차를 만든다. 동시에 같은 회차를 만들려 한 쪽이 있으면 여기서 중복 키가 난다. */
    private Draw open(String drawId, Instant now) {
        Draw created = Draw.open(drawId, now);
        draws.saveAndFlush(created);
        return created;
    }

    private Attempt judgeExisting(DrawEntry entry, DrawEntryCommand command) {
        // 같은 응모면 저장된 결과를 재생하고, 아니면 저장된 응모를 건드리지 않고 거절한다.
        // 다른 회차로 왔다면 위에서 그 회차를 만들었을 수 있지만, 거절하면 롤백되어 남지 않는다.
        List<String> differing = entry.differingFields(command.drawId(), command.customerId());
        if (!differing.isEmpty()) {
            throw new MockException(ErrorCode.KEY_PAYLOAD_MISMATCH,
                    "같은 응모 id 로 다른 내용이 요청되었습니다. " + String.join(", ", differing) + " 이(가) 다릅니다.");
        }
        return new Attempt(entry, true);
    }

    /** 한 번의 시도 결과. */
    public record Attempt(DrawEntry entry, boolean replayed) {
    }
}
