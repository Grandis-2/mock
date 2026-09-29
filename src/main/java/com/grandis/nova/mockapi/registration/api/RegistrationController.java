package com.grandis.nova.mockapi.registration.api;

import com.grandis.nova.mockapi.registration.application.RegisterResult;
import com.grandis.nova.mockapi.registration.application.RegistrationReader;
import com.grandis.nova.mockapi.registration.application.RegistrationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/external/reservations")
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
    @PostMapping
    public ResponseEntity<RegistrationResponse> register(
            @RequestHeader(IDEMPOTENCY_KEY) String key,
            @Valid @RequestBody RegisterRequest request) {
        Identifiers.requireLength(IDEMPOTENCY_KEY, key);

        RegisterResult result = service.register(key, request.toCommand());

        return ResponseEntity.status(HttpStatus.CREATED)
                .header(IDEMPOTENT_REPLAY, String.valueOf(result.replayed()))
                .header(CONFIG_VERSION, String.valueOf(result.configVersion()))
                .body(RegistrationResponse.from(result.registration()));
    }

    /** 번호로 단건 조회. 등록 응답과 같은 형식이고 취소된 등록도 돌려준다. */
    @GetMapping("/{externalNumber}")
    public RegistrationResponse findByNumber(@PathVariable String externalNumber) {
        return RegistrationResponse.from(reader.findByNumber(externalNumber));
    }

    /** 키로 등록 상태 조회. 응답 유실 뒤 재시도 전에 워커가 부른다. 404 는 "등록되지 않았다" 의 확정 근거다. */
    @GetMapping("/by-key/{externalKey}")
    public KeyStatusResponse findByKey(@PathVariable String externalKey) {
        Identifiers.requireLength("externalKey", externalKey);
        return KeyStatusResponse.from(reader.findByKey(externalKey));
    }
}
