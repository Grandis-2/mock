package com.grandis.nova.mockapi.registration.api;

import com.grandis.nova.mockapi.global.error.ErrorCode;
import com.grandis.nova.mockapi.global.error.MockException;
import com.grandis.nova.mockapi.global.validation.Identifiers;
import com.grandis.nova.mockapi.registration.application.RegisterResult;
import com.grandis.nova.mockapi.registration.application.RegistrationReader;
import com.grandis.nova.mockapi.registration.application.RegistrationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 예약 등록 · 조회.
 *
 * <p>{@code produces} · {@code consumes} 를 매핑에 둔다. 헤더가 틀린 요청을 핸들러에 들어가기 전에 걸러야
 * 한다 — 없으면 {@code Accept: text/plain} 등록이 커밋된 뒤에 응답을 못 써 406 이 나가고, 워커는 4xx 를
 * "확정 거절" 로 읽어 실제로는 된 등록을 안 된 것으로 믿는다.
 */
@RestController
@RequestMapping(path = "/external/reservations", produces = MediaType.APPLICATION_JSON_VALUE)
public class RegistrationController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String IDEMPOTENT_REPLAY = "X-Idempotent-Replay";
    static final String CONFIG_VERSION = "X-Mock-Config-Version";

    private final RegistrationService service;
    private final RegistrationReader reader;

    public RegistrationController(RegistrationService service, RegistrationReader reader) {
        this.service = service;
        this.reader = reader;
    }

    /** 예약 등록 (멱등). 재생도 201 이다 — 워커는 헤더로 새 등록과 재생을 구분한다. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RegistrationResponse> register(
            @RequestHeader(IDEMPOTENCY_KEY) String key,
            @Valid @RequestBody RegisterRequest request) {
        Identifiers.requireFormat(IDEMPOTENCY_KEY, key);
        // 본문의 키와 헤더의 키는 같은 값(preorder_token)이다. 다르면 어느 쪽으로 멱등 처리할지 정할 수 없다.
        // 처리 전에 거절하므로 저장된 것은 없다.
        if (!key.equals(request.ourReservationId())) {
            throw new MockException(ErrorCode.INVALID_REQUEST,
                    "ourReservationId 가 " + IDEMPOTENCY_KEY + " 와 다릅니다.");
        }

        RegisterResult result = service.register(key, request.toCommand());

        return ResponseEntity.status(HttpStatus.CREATED)
                .header(IDEMPOTENT_REPLAY, String.valueOf(result.replayed()))
                .header(CONFIG_VERSION, String.valueOf(result.configVersion()))
                .body(RegistrationResponse.from(result.registration()));
    }

    /** 번호로 단건 조회. 등록 응답과 같은 형식이고 취소된 등록도 돌려준다. */
    @GetMapping("/{externalNumber}")
    public RegistrationResponse findByNumber(@PathVariable String externalNumber) {
        // 형식이 틀린 번호는 400 이다. 조회까지 보내면 404 NOT_FOUND("그 기록이 없다")로 나가 뜻이 섞인다.
        Identifiers.requireFormat("externalNumber", externalNumber);
        return RegistrationResponse.from(reader.findByNumber(externalNumber));
    }

    /** 키로 등록 상태 조회. 응답 유실 뒤 재시도 전에 워커가 부른다. 404 는 "지금 등록이 없다" 는 뜻이다. */
    @GetMapping("/by-key/{externalKey}")
    public KeyStatusResponse findByKey(@PathVariable String externalKey) {
        Identifiers.requireFormat("externalKey", externalKey);
        return KeyStatusResponse.from(reader.findByKey(externalKey));
    }
}
