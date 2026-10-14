package com.grandis.nova.mockapi.global.chaos;

import com.grandis.nova.mockapi.global.chaos.FaultStorage.StoredFault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 키마다 걸어 둔 결함을 걸고 꺼낸다. 결함 API · 초기화 · 등록 파트가 쓰는 입구다.
 *
 * <p>결함을 실제로 두는 곳은 {@link FaultStorage} 다 — 한 대면 메모리(기본), 여러 대면 Redis
 * ({@code mock.shared-state=redis}). 여러 대를 메모리로 띄우면 결함을 건 키의 요청이 다른 서버로 가서 발동하지 않는다.
 * 이름의 "InMemory" 는 메모리뿐이던 때 붙인 것이다.
 */
@Component
public class InMemoryFaultStore implements FaultHook {

    private static final Logger log = LoggerFactory.getLogger(InMemoryFaultStore.class);

    private final FaultStorage faults;

    @Autowired
    public InMemoryFaultStore(FaultStorage faults) {
        this.faults = faults;
    }

    /** 메모리에 두는 보관소. 단위 시험이 쓴다. */
    public InMemoryFaultStore() {
        this(new MemoryFaultStorage());
    }

    /** 같은 키에 이미 결함이 있으면 새 결함으로 바꾼다. 기다리는 시간이 없는 결함(응답 유실)이다. */
    public void inject(String externalKey, FaultType faultType) {
        inject(externalKey, faultType, 0);
    }

    /** 같은 키에 이미 결함이 있으면 새 결함으로 바꾼다. 범위 검사는 결함 API 가 한다. */
    public void inject(String externalKey, FaultType faultType, long delayMs) {
        faults.put(externalKey, new StoredFault(faultType, delayMs));
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
        StoredFault fault = take(externalKey, FaultType.SLOW_SUCCESS);
        if (fault == null) {
            return 0;
        }
        DefaultFailureInjector.announceMore(fault.delayMs());
        DefaultFailureInjector.pause(fault.delayMs(), "느린 성공 대기 중 중단됐다");
        return fault.delayMs();
    }

    /**
     * 그 종류의 결함이 걸려 있으면 꺼내며 지운다. 다른 종류의 결함은 그대로 둔다. 꺼내기와 지우기를 한 번에 하는 것은
     * 보관소({@link FaultStorage#take})가 맡는다.
     */
    private StoredFault take(String externalKey, FaultType type) {
        StoredFault fault = faults.take(externalKey, type);
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
        return faults.contains(externalKey);
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
