package com.grandis.nova.mockapi.control.api;

import com.grandis.nova.mockapi.global.chaos.MockConfigStore;
import com.grandis.nova.mockapi.global.config.MockProperties;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 지연·실패 설정 조회와 변경. 시연 조작 패널이다.
 *
 * <p>이 경로에는 <b>지연·실패를 주입하지 않는다.</b> 주입하면 실패율을 1.0 으로 올린 뒤 되돌릴 수
 * 없게 되어 시연 중에 Mock 을 살릴 방법이 사라진다.
 *
 * <p>워커가 부르는 API 가 아니므로 잘못된 입력은 전부 400 이다. 500 은 워커에게 "일시 실패,
 * 다시 보내라" 는 뜻이라 조작 패널이 낼 응답이 아니다.
 */
@RestController
@RequestMapping("/external/config")
public class MockConfigController {

    private final MockConfigStore store;
    private final MockProperties properties;

    public MockConfigController(MockConfigStore store, MockProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    @GetMapping
    public ConfigResponse get() {
        return ConfigResponse.from(store.applied(), properties);
    }

    /**
     * 재기동 없이 값을 바꾼다. 진행 중인 등록 시도는 시작 시점 설정으로 끝나고, 변경은 이후
     * 시도부터 적용된다.
     *
     * <p>응답은 요청값이 아니라 저장소가 돌려준 <b>실제 적용값</b>이다. 받은 값을 그대로
     * 되돌려주면 적용 실패를 저장 성공으로 표시할 수 있다(요구사항 4.4).
     */
    @PutMapping
    public ConfigResponse update(@Valid @RequestBody ConfigUpdateRequest request) {
        return ConfigResponse.from(store.update(
                request.registerLatencyMs(),
                request.failureRate(),
                request.failureModeOrDefault()), properties);
    }
}
