package com.grandis.nova.mockapi.global.chaos;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 키마다 걸어둔 결함을 들고 있는 메모리 보관소.
 *
 * <p>DB 에 두지 않는다. 결함은 시험·시연 중에만 쓰는 일회용 장치이고, 재기동하면 사라지는 편이
 * 매번 같은 초기 상태에서 시작하는 데 유리하다. 설정과 같은 이유로 <b>Mock 은 프로세스 1개로
 * 띄운다</b> — 2개 이상이면 결함을 건 키의 요청이 다른 쪽으로 가서 발동하지 않는다.
 */
@Component
public class InMemoryFaultStore implements FaultHook {

    /** 걸어 둔 결함 하나. 기다리는 시간은 느린 성공에만 쓴다(응답 유실은 0). */
    private record Fault(FaultType type, long delayMs) {
    }

    private static final Logger log = LoggerFactory.getLogger(InMemoryFaultStore.class);

    private final ConcurrentHashMap<String, Fault> faults = new ConcurrentHashMap<>();

    /** 같은 키에 이미 결함이 있으면 새 결함으로 바꾼다. 기다리는 시간이 없는 결함(응답 유실)이다. */
    public void inject(String externalKey, FaultType faultType) {
        inject(externalKey, faultType, 0);
    }

    /** 같은 키에 이미 결함이 있으면 새 결함으로 바꾼다. 범위 검사는 결함 API 가 한다. */
    public void inject(String externalKey, FaultType faultType, long delayMs) {
        faults.put(externalKey, new Fault(faultType, delayMs));
    }

    /**
     * 꺼내는 것과 지우는 것을 한 번에 한다.
     *
     * <p>조회하고 나서 따로 지우면 같은 키의 동시 등록 두 건이 모두 true 를 받아 결함이 두 번
     * 발동한다. 등록은 한 건뿐인데 응답이 두 번 유실되면 재시도가 저장된 성공에 닿지 못한다.
     *
     * <p>종류까지 맞춰 지우므로 다른 종류의 결함은 건드리지 않는다.
     */
    @Override
    public boolean consumeResponseLost(String externalKey) {
        return take(externalKey, FaultType.RESPONSE_LOST_AFTER_COMMIT) != null;
    }

    /**
     * 느린 성공 결함을 <b>먼저 꺼낸 뒤</b> 기다린다({@link FaultHook#holdBeforeCommit} 계약). 기다리는 동안 들어온
     * 같은 키 재시도는 결함이 이미 없어 바로 진행한다 — 시연의 "404 → 같은 키 재시도" 가 그래서 보인다.
     */
    @Override
    public long holdBeforeCommit(String externalKey) {
        Fault fault = take(externalKey, FaultType.SLOW_SUCCESS);
        if (fault == null) {
            return 0;
        }
        DefaultFailureInjector.announceMore(fault.delayMs());
        DefaultFailureInjector.pause(fault.delayMs(), "느린 성공 대기 중 중단됐다");
        return fault.delayMs();
    }

    /**
     * 그 종류의 결함이 걸려 있으면 꺼내며 지운다. 다른 종류의 결함은 그대로 둔다.
     *
     * <p>읽기와 지우기를 {@code computeIfPresent} 한 번으로 한다. {@code get} 뒤에 {@code remove(key, value)} 로 나누면
     * 그 틈에 같은 종류 · 같은 대기 시간으로 다시 건 결함까지 지운다 — {@link Fault} 가 record 라 값이 같으면 같은 결함으로
     * 보기 때문이다. 한 번에 하면 동시에 꺼내도 한 쪽만 받고, 꺼낸 뒤에 새로 건 결함은 남는다.
     */
    private Fault take(String externalKey, FaultType type) {
        AtomicReference<Fault> taken = new AtomicReference<>();
        faults.computeIfPresent(externalKey, (key, fault) -> {
            if (fault.type() != type) {
                return fault;
            }
            taken.set(fault);
            return null;
        });
        Fault fault = taken.get();
        if (fault != null) {
            // 실패 주입 로그(DefaultFailureInjector)와 같은 모양이다. be 로그와 키로 맞대 본다.
            // 기다리는 시간은 느린 성공에만 있다 — 응답 유실에 delayMs=0 을 찍으면 "0ms 기다렸다" 로 읽힌다
            if (fault.type() == FaultType.SLOW_SUCCESS) {
                log.info("결함 발동 {} key={} delayMs={}", fault.type(), externalKey, fault.delayMs());
            } else {
                log.info("결함 발동 {} key={}", fault.type(), externalKey);
            }
        }
        return fault;
    }

    /** 시험용 — 결함이 아직 걸려 있는가. 꺼내기와 기다리기의 순서를 볼 때 쓴다. */
    boolean pending(String externalKey) {
        return faults.containsKey(externalKey);
    }

    /**
     * 걸려 있는 결함을 모두 버린다. 초기화가 부른다.
     *
     * <p>발동하지 않고 남은 결함을 지우는 것이 목적이다. 남겨두면 다음 시험에서 엉뚱한 키의 응답이
     * 유실되고, 그 시험은 결함을 걸지 않았으므로 원인을 찾기 어렵다.
     */
    public void clear() {
        faults.clear();
    }
}
