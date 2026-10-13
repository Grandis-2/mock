package com.grandis.nova.mockapi.global.chaos;

import com.grandis.nova.mockapi.global.config.MockProperties;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 지연·실패 설정을 들고 있는 보관소. 요청 처리는 늘 이 메모리 값을 읽는다.
 *
 * <p>한 대({@code mock.shared-state=memory}, 기본)면 값은 여기에만 있고, 재기동하면 application.yml 의 mock.* 로
 * 돌아간다. 여러 대({@code redis})면 {@link ConfigSync} 가 서버끼리 나눈다 — 바꾸면 공유 저장소에 쓰고, 다른
 * 서버가 바꾸면 알림을 받아 여기를 바꾼다. 그때는 재기동해도 공유된 설정이 남는다.
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

    /** 여러 대일 때만 있다. 없으면 메모리에서 혼자 버전을 올린다. */
    private final ConfigSync sync;

    @Autowired
    public MockConfigStore(MockProperties properties, ObjectProvider<ConfigSync> sync) {
        this(properties, sync.getIfAvailable());
    }

    /** @param sync 서버끼리 나누는 길. 한 대면 null */
    public MockConfigStore(MockProperties properties, ConfigSync sync) {
        this.sync = sync;
        Applied defaults = new Applied(
                new ConfigSnapshot(
                        properties.registerLatencyMs(),
                        properties.failureRate(),
                        properties.failureMode(),
                        0),
                now());
        this.current = new AtomicReference<>(defaults);
        if (sync != null) {
            // 기동 중에 다른 서버의 알림이 먼저 들어와 더 새 버전을 넣었을 수 있다. 그보다 옛것이면 덮지 않는다.
            // 같은 0 이면 공유된 쪽을 따른다 — 먼저 뜬 서버가 올린 기본값이 기준이다.
            Applied shared = sync.start(defaults, this::accept);
            current.updateAndGet(old ->
                    shared.snapshot().configVersion() >= old.snapshot().configVersion() ? shared : old);
        }
    }

    /**
     * 등록 시도 하나가 시작할 때 등록 파트가 부른다.
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
    public Applied update(int registerLatencyMs, LatencyTail latencyTail, double failureRate,
                          FailureMode failureMode, List<MixedResponse> mixedResponses) {
        if (sync != null) {
            // 쓰기와 적용을 다시 읽기(알림 · 안전망)와 같은 잠금으로 묶는다. 그 사이에 다른 서버의 같은 버전 설정이
            // 적용되면, 뒤이은 적용이 같은 버전이라는 이유로 옛것을 덮는다.
            synchronized (sync) {
                // 버전은 공유 저장소가 정한다. 0 은 자리만 채운 값이다.
                ConfigSnapshot next = new ConfigSnapshot(
                        registerLatencyMs, failureRate, failureMode, 0, latencyTail, mixedResponses);
                Applied stored = sync.publish(next, now());
                accept(stored);
                return stored;
            }
        }
        return current.updateAndGet(old -> new Applied(
                new ConfigSnapshot(
                        registerLatencyMs,
                        failureRate,
                        failureMode,
                        old.snapshot().configVersion() + 1,
                        latencyTail,
                        mixedResponses),
                now()));
    }

    /**
     * 다른 서버가 바꾼 설정, 또는 공유 저장소에 쓴 결과를 받는다. 알림 순서가 뒤바뀌어 옛 버전이 늦게 와도
     * 되돌리지 않는다 — 더 작은 버전은 받지 않는다.
     *
     * <p>같은 버전은 받는다. 공유 저장소가 설정을 잃은 틈에 서로 다른 설정이 같은 버전 번호를 가질 수 있는데, 그때
     * {@link ConfigSync} 가 공유 저장소의 것을 따르라고 넘겨준다. 그렇지 않은 같은 버전은 같은 설정이라 바꿔도 그대로다.
     */
    void accept(Applied incoming) {
        current.updateAndGet(old ->
                incoming.snapshot().configVersion() >= old.snapshot().configVersion() ? incoming : old);
    }

    /** 섞을 종류를 고르지 않고 바꾼다. MIXED 면 전부를 섞는다({@link ConfigSnapshot}). */
    public Applied update(int registerLatencyMs, LatencyTail latencyTail, double failureRate,
                          FailureMode failureMode) {
        return update(registerLatencyMs, latencyTail, failureRate, failureMode, List.of());
    }

    /**
     * 꼬리 없이 바꾼다. 설정 API 에서 꼬리 칸을 생략한 것과 같다 — 앞 설정의 꼬리를 남기지 않는다.
     * 꼬리를 들이기 전부터 시험들이 이 모양으로 설정을 바꾸고 되돌린다.
     */
    public Applied update(int registerLatencyMs, double failureRate, FailureMode failureMode) {
        return update(registerLatencyMs, LatencyTail.NONE, failureRate, failureMode);
    }

    /**
     * 밀리초까지만 남긴다. 다른 시각 칸(등록·취소·결함)이 모두 밀리초로 잘려 있어
     * 여기만 나노초면 같은 실행의 기록을 나란히 놓고 볼 때 정밀도가 어긋난다.
     */
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }
}
