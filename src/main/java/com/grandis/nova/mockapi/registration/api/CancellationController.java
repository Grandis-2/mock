package com.grandis.nova.mockapi.registration.api;

import com.grandis.nova.mockapi.global.error.ErrorCode;
import com.grandis.nova.mockapi.global.error.MockException;
import com.grandis.nova.mockapi.global.validation.Identifiers;
import com.grandis.nova.mockapi.registration.application.CancellationService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 예약 취소. 헤더 검사는 등록과 같은 이유로 매핑에 둔다({@link RegistrationController} 참고). */
@RestController
@RequestMapping(path = "/external/cancellations", produces = MediaType.APPLICATION_JSON_VALUE)
public class CancellationController {

    private final CancellationService service;

    public CancellationController(CancellationService service) {
        this.service = service;
    }

    /** 예약 취소. 미등록 · 이미 취소된 대상도 200 이다. 반복 호출의 업무 효과는 한 번이다. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public CancelResponse cancel(@Valid @RequestBody CancelRequest request) {
        String key = request.externalKey();
        String number = request.reservationNo();
        if (key == null && number == null) {
            throw new MockException(ErrorCode.INVALID_REQUEST, "externalKey 와 reservationNo 중 하나는 있어야 합니다.");
        }
        if (key != null) {
            Identifiers.requireFormat("externalKey", key);
        }
        if (number != null) {
            Identifiers.requireFormat("reservationNo", number);
        }
        return CancelResponse.from(service.cancel(key, number));
    }
}
