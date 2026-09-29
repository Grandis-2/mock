package com.grandis.nova.mockapi.control.api;

import com.grandis.nova.mockapi.control.application.ResetService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 기록 초기화. 시험 · 시연용이다. 따로 막지 않으므로 공유 환경에 띄우면 주소를 아는 누구나 기록을
 * 지울 수 있다. 실수 실행은 확인 문자열({@code "RESET"})로만 막는다.
 *
 * <p>이전 시험의 잔량이 다음 대조에 섞이지 않게 한다. 부하 시험을 반복할 때는 매 실행 전에 이걸
 * 부르는 흐름이 된다.
 *
 * <p>이 경로에는 <b>지연·실패를 주입하지 않는다.</b> 실패율을 1.0 으로 올려둔 상태에서도 초기화할 수
 * 있어야 한다.
 *
 * <p>워커가 부르는 API 가 아니므로 잘못된 입력은 전부 400 이다.
 */
@RestController
@RequestMapping("/external/reset")
public class ResetController {

    private final ResetService service;

    public ResetController(ResetService service) {
        this.service = service;
    }

    /** 전체 초기화다. ERD 에 실행 범위 칸이 없어 범위를 나눠 지울 수 없다. */
    @PostMapping
    public ResetResponse reset(@Valid @RequestBody ResetRequest request) {
        return new ResetResponse(service.reset());
    }
}
