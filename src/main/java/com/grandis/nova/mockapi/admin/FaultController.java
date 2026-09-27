package com.grandis.nova.mockapi.admin;

import com.grandis.nova.mockapi.admin.dto.FaultRequest;
import com.grandis.nova.mockapi.admin.dto.FaultResponse;
import com.grandis.nova.mockapi.global.chaos.InMemoryFaultStore;
import jakarta.validation.Valid;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 결함 주입. 시험 환경 전용이며 운영에서는 노출하지 않는다.
 *
 * <p>과제 원문에는 없지만 요구사항 8장이 "외부 성공 응답 유실을 재현해 원래 신청의 처리 지속 확인"
 * 을 요구하므로 실질적으로 필수다. 5% 확률에 기대지 않고 정확히 재현하는 수단이다.
 *
 * <p>이 경로에는 <b>지연·실패를 주입하지 않는다.</b> 결함을 걸려는 호출까지 5% 확률로 실패하면
 * 시연 중에 무엇이 실패한 것인지 분간할 수 없다.
 *
 * <p>워커가 부르는 API 가 아니므로 잘못된 입력은 전부 400 이다.
 */
@RestController
@RequestMapping("/external/faults")
public class FaultController {

    private final InMemoryFaultStore store;

    public FaultController(InMemoryFaultStore store) {
        this.store = store;
    }

    /**
     * 키에 결함을 걸어둔다. 이미 걸린 키에 다시 걸면 새 결함으로 바뀐다.
     *
     * <p>등록된 적이 있는 키에도 걸 수 있지만, 재생 경로에는 커밋이 없어 발동하지 않고 그대로
     * 남는다. 남은 결함은 {@code POST /external/reset} 이 지운다.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FaultResponse inject(@Valid @RequestBody FaultRequest request) {
        store.inject(request.externalKey(), request.faultType());
        // 시각은 밀리초까지다. 등록 원장이 저장·응답을 맞추려고 자르는 것과 같은 자리수로 맞춘다.
        return new FaultResponse(request.externalKey(), request.faultType(),
                Instant.now().truncatedTo(ChronoUnit.MILLIS));
    }
}
