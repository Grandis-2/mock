package com.grandis.nova.mockapi.global.chaos;

import com.grandis.nova.mockapi.global.config.MockProperties;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * 지연·실패 설정을 들고 있는 메모리 보관소.
 *
 * <p>ERD 가 설정 테이블을 두지 않기로 했으므로 값은 여기에만 있고, 재기동하면
 * application.yml 의 mock.* 로 돌아간다.
 */
@Component
public class MockConfigStore implements ConfigProvider {

    /**
     * 설정과 적용 시각을 한 묶음으로 둔다.
     *
     * <p>따로 두면 "설정은 바뀌었는데 시각은 아직 옛것" 인 틈이 생기고, 그 사이에 조회가 끼면
     * 어긋난 값을 함께 내보낸다. 한 묶음을 통째로 교체하면 그 틈이 없다.
     */
    public record Applied(ConfigSnapshot snapshot, Instant appliedAt) {
    }

    private final AtomicReference<Applied> current;

    public MockConfigStore(MockProperties properties) {
        this.current = new AtomicReference<>(new Applied(
                new ConfigSnapshot(
                        properties.registerLatencyMs(),
                        properties.failureRate(),
                        properties.failureMode(),
                        0),
                now()));
    }

    /**
     * 등록 시도 하나가 시작할 때 A 가 부른다.
     *
     * <p>받아간 스냅샷은 record 라 불변이다. 처리 도중 설정이 바뀌어도 그 시도는 이 값으로 끝난다.
     */
    @Override
    public ConfigSnapshot snapshot() {
        return current.get().snapshot();
    }

    /** 조회 API 용. 적용 시각까지 필요할 때 쓴다. */
    public Applied applied() {
        return current.get();
    }

    /**
     * 설정을 바꾸고 configVersion 을 1 올린다.
     *
     * <p>범위 검사는 여기서 하지 않는다. 설정 테이블이 없어 DB 제약이 없으므로 API 계층이 맡는다.
     *
     * @return 실제로 적용된 값. 요청값을 그대로 돌려주지 않는다
     */
    public Applied update(int registerLatencyMs, double failureRate, FailureMode failureMode) {
        return current.updateAndGet(old -> new Applied(
                new ConfigSnapshot(
                        registerLatencyMs,
                        failureRate,
                        failureMode,
                        old.snapshot().configVersion() + 1),
                now()));
    }

    /**
     * 밀리초까지만 남긴다. 다른 시각 칸(등록·취소·결함)이 모두 밀리초로 잘려 있어
     * 여기만 나노초면 같은 실행의 기록을 나란히 놓고 볼 때 정밀도가 어긋난다.
     */
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }
}
