package com.grandis.nova.mockapi.admin;

import com.grandis.nova.mockapi.global.chaos.InMemoryFaultStore;
import com.grandis.nova.mockapi.registration.RegistrationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 시험 사이에 기록을 비운다.
 *
 * <p>등록 행과 취소 표식은 같은 표의 행이라 한 번에 지워진다. 멱등 판정도 그 키 행이 근거이므로
 * 따로 지울 것이 없다.
 *
 * <p><b>설정은 건드리지 않는다.</b> 이 클래스가 설정 저장소를 아예 주입받지 않는 것이 그 증거다.
 * 지연·실패율과 {@code configVersion} 은 그대로 남는다 — 설정이 메모리라 초기화가 함께 지우면
 * 시연 중 매번 조작 패널을 다시 채워야 한다.
 *
 * <p>컨트롤러가 아니라 여기에 트랜잭션을 두는 이유는 지우기가 전부 되거나 전부 안 되게 하기
 * 위해서다.
 *
 * <p>부하 시험을 반복하며 수만 건을 지우게 되면 한 행씩 지우는 비용이 문제가 될 수 있는데, 그때
 * {@code TRUNCATE} 로 바꿀지는 부하 기준을 Mock 에도 적용할지가 정해진 뒤에 A 와 합의할 일이다.
 */
@Service
public class ResetService {

    private final RegistrationRepository repository;
    private final InMemoryFaultStore faults;

    public ResetService(RegistrationRepository repository, InMemoryFaultStore faults) {
        this.repository = repository;
        this.faults = faults;
    }

    /**
     * 등록 기록과 걸려 있는 결함을 지운다.
     *
     * <p>세어 보고 지운다. 격리 수준이 READ COMMITTED 라 같은 트랜잭션 안에서도 문장마다 최신
     * 커밋을 다시 보므로, 두 문장 사이에 등록이 커밋되면 돌려주는 건수가 실제로 지운 수보다 적어질
     * 수 있다. <b>초기화는 시험 사이에만 부른다는 전제로 둔다</b> — 초기화 중에 등록이 들어온다면
     * 그 시험 자체가 이미 성립하지 않는다. 전제 없이 정확히 세려면 한 문장으로 지우면서 영향 행
     * 수를 받아야 하는데, 그건 A 의 리포지토리에 메서드를 더하는 일이라 합의가 필요하다.
     *
     * <p>결함을 마지막에 지운다. 메모리는 롤백되지 않으므로, 먼저 지우면 지우기가 실패해 행이
     * 그대로 남았는데 결함만 사라진 상태가 된다.
     *
     * @return 지운 행 수
     */
    @Transactional
    public long reset() {
        long deletedCount = repository.count();
        repository.deleteAll();
        faults.clear();
        return deletedCount;
    }
}
