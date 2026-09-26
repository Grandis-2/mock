package com.grandis.nova.mockapi.global.chaos;

import java.util.concurrent.ConcurrentHashMap;
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

    private final ConcurrentHashMap<String, FaultType> faults = new ConcurrentHashMap<>();

    /** 같은 키에 이미 결함이 있으면 새 결함으로 바꾼다. */
    public void inject(String externalKey, FaultType faultType) {
        faults.put(externalKey, faultType);
    }

    /**
     * 꺼내는 것과 지우는 것을 한 번에 한다.
     *
     * <p>조회하고 나서 따로 지우면 같은 키의 동시 등록 두 건이 모두 true 를 받아 결함이 두 번
     * 발동한다. 등록은 한 건뿐인데 응답이 두 번 유실되면 재시도가 저장된 성공에 닿지 못한다.
     *
     * <p>값까지 맞춰 지우므로 다른 종류의 결함은 건드리지 않는다. 한 인수짜리 {@code remove} 로
     * 지우면 응답 유실이 아닌 결함까지 소리 없이 사라진다.
     */
    @Override
    public boolean consumeResponseLost(String externalKey) {
        return faults.remove(externalKey, FaultType.RESPONSE_LOST_AFTER_COMMIT);
    }
}
