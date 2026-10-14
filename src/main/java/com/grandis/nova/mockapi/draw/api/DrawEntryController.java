package com.grandis.nova.mockapi.draw.api;

import com.grandis.nova.mockapi.draw.application.DrawEntryResult;
import com.grandis.nova.mockapi.draw.application.DrawEntryService;
import com.grandis.nova.mockapi.global.error.ErrorCode;
import com.grandis.nova.mockapi.global.error.MockException;
import com.grandis.nova.mockapi.global.validation.Identifiers;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 럭키드로우 응모. be worker 가 응모비 결제 완료 한 건마다 부른다.
 *
 * <p>{@code produces} · {@code consumes} 를 매핑에 두는 이유는 등록({@code RegistrationController})과 같다 — 헤더가 틀린
 * 요청을 핸들러 전에 거른다. 없으면 {@code Accept: text/plain} 응모가 저장된 뒤에 응답을 못 써 406 이 나가고, 워커는 4xx 를
 * 확정 거절로 읽어 실제로는 들어간 응모를 안 들어간 것으로 믿는다.
 */
@RestController
@RequestMapping(path = "/external/draw-entries", produces = MediaType.APPLICATION_JSON_VALUE)
public class DrawEntryController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String IDEMPOTENT_REPLAY = "X-Idempotent-Replay";
    static final String CONFIG_VERSION = "X-Mock-Config-Version";

    private final DrawEntryService service;

    public DrawEntryController(DrawEntryService service) {
        this.service = service;
    }

    /** 응모 (멱등). 재생도 201 이다 — 등록과 같다. 워커는 헤더로 새 응모와 재생을 구분한다. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DrawEntryResponse> enter(
            @RequestHeader(IDEMPOTENCY_KEY) String key,
            @Valid @RequestBody DrawEntryRequest request) {
        Identifiers.requireFormat(IDEMPOTENCY_KEY, key);
        // 회차 id 는 회차 조회 · 추첨의 경로에 들어간다. 여기서 받은 회차를 거기서 찾지 못하는 일이 없게 같은 규칙으로 본다.
        Identifiers.requireFormat("drawId", request.drawId());
        // 본문의 응모 id 와 헤더의 키는 같은 값(be 봉투의 aggregateId)이다. 다르면 어느 쪽으로 멱등 처리할지 정할 수 없다.
        // 처리 전에 거절하므로 저장된 것은 없다.
        if (!key.equals(request.entryId())) {
            throw new MockException(ErrorCode.INVALID_REQUEST, "entryId 가 " + IDEMPOTENCY_KEY + " 와 다릅니다.");
        }

        DrawEntryResult result = service.enter(key, request.toCommand());

        return ResponseEntity.status(HttpStatus.CREATED)
                .header(IDEMPOTENT_REPLAY, String.valueOf(result.replayed()))
                .header(CONFIG_VERSION, String.valueOf(result.configVersion()))
                .body(DrawEntryResponse.from(result.entry()));
    }
}
