package com.grandis.nova.mockapi.global.chaos;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 서버 메모리에 결함을 둔다. 한 대일 때의 기본값이다({@code mock.shared-state=memory}).
 *
 * <p>DB 에 두지 않는다. 결함은 시험 · 시연 중에만 쓰는 일회용 장치이고, 재기동하면 사라지는 편이 매번 같은 초기 상태에서
 * 시작하는 데 유리하다.
 */
@Component
@ConditionalOnProperty(prefix = "mock", name = "shared-state", havingValue = "memory", matchIfMissing = true)
public class MemoryFaultStorage implements FaultStorage {

    private final ConcurrentHashMap<String, StoredFault> faults = new ConcurrentHashMap<>();

    @Override
    public void put(String externalKey, StoredFault fault) {
        faults.put(externalKey, fault);
    }

    /**
     * 읽기와 지우기를 {@code computeIfPresent} 한 번으로 한다. {@code get} 뒤에 {@code remove(key, value)} 로 나누면
     * 그 틈에 같은 종류 · 같은 대기 시간으로 다시 건 결함까지 지운다 — {@link StoredFault} 가 record 라 값이 같으면 같은
     * 결함으로 보기 때문이다. 한 번에 하면 동시에 꺼내도 한 쪽만 받고, 꺼낸 뒤에 새로 건 결함은 남는다.
     */
    @Override
    public StoredFault take(String externalKey, FaultType type) {
        AtomicReference<StoredFault> taken = new AtomicReference<>();
        faults.computeIfPresent(externalKey, (key, fault) -> {
            if (fault.type() != type) {
                return fault;
            }
            taken.set(fault);
            return null;
        });
        return taken.get();
    }

    @Override
    public boolean contains(String externalKey) {
        return faults.containsKey(externalKey);
    }

    @Override
    public void clear() {
        faults.clear();
    }
}
