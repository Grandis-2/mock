package com.grandis.nova.mockapi.registration.application;

import com.grandis.nova.mockapi.global.error.ErrorCode;
import com.grandis.nova.mockapi.global.error.MockException;
import com.grandis.nova.mockapi.registration.domain.Registration;
import com.grandis.nova.mockapi.registration.domain.RegistrationRepository;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 등록 원장 조회. 번호 조회 · 키 조회 · 목록 조회를 맡는다.
 *
 * <p>지연·실패를 적용하지 않는다(명세). 워커가 결과를 확인하려고 부르는 길이라, 실패율을 올린 상태에서도
 * 막히면 안 된다.
 */
@Component
public class RegistrationReader {

    private final RegistrationRepository repository;

    public RegistrationReader(RegistrationRepository repository) {
        this.repository = repository;
    }

    /**
     * 번호로 한 건. 취소된 등록도 숨기지 않는다.
     *
     * @throws MockException 없는 번호(404)
     */
    @Transactional(readOnly = true)
    public Registration findByNumber(String externalNumber) {
        return repository.findByExternalNumber(externalNumber)
                .orElseThrow(() -> new MockException(ErrorCode.NOT_FOUND, "등록되지 않았습니다."));
    }

    /**
     * 키로 한 행. 등록이든 취소 표식이든 그 키의 행을 돌려준다.
     *
     * <p><b>공유 잠금으로 읽는다.</b> 같은 키의 등록이 커밋되기 전이면 끝날 때까지 기다린다. 그냥 읽으면
     * 진행 중인 등록을 못 본 채 404 가 나가고, 워커는 그걸 "등록 안 됨" 으로 믿고 재등록해 중복 등록이 된다.
     *
     * <p>지연은 등록 트랜잭션 밖이라 잠금을 쥐고 있지 않다. 등록 지연을 크게 잡아도 이 조회는 그만큼
     * 기다리지 않고, 지연 중인 등록은 404 로 보인다. 그래서 404 는 "지금 없음" 이지 "앞으로도 없음" 이
     * 아니다 — 워커는 같은 키로 다시 보낸다. 같은 키로 취소하는 것은 예약을 취소로 끝낼 때뿐이다(명세 키 조회 절).
     *
     * <p>격리 수준은 등록과 같게 READ COMMITTED 로 정한다({@link RegistrationWriter} 참고). 잠금 읽기라
     * REPEATABLE READ 에서는 없는 키에 갭 락을 걸어 그 틈의 새 등록을 막는다.
     *
     * @throws MockException 등록·취소 표식 어느 것도 없는 키(404)
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public Registration findByKey(String externalKey) {
        return repository.findByKeyForShare(externalKey)
                .orElseThrow(() -> new MockException(ErrorCode.NOT_FOUND, "키에 대한 기록이 없습니다."));
    }

    /**
     * 원장 목록 한 페이지. 정합성 검사가 Mock 쪽 등록을 전부 받아 갈 때 부른다.
     *
     * <p><b>외부 키 순서로 커서 다음부터 읽는다</b>(오프셋이 아니다). 키는 바뀌지 않으므로 페이지를 넘기는 사이에
     * 등록 · 취소가 들어와도 원래 있던 행은 정확히 한 번씩 나온다. 오프셋으로 넘기면 앞쪽에 새 행이 끼는 순간 한
     * 행이 두 번 나오거나 빠진다. 넘기는 중에 새로 생긴 행은 나올 수도 있고 안 나올 수도 있다 — 검사하는 쪽이 가린다.
     *
     * <p>잠그지 않는다. 진행 중인 등록을 기다리지 않아도 되고(키 조회와 다르다), 목록 읽기가 등록을 막으면 안 된다.
     *
     * <p>{@code size + 1} 건을 읽어 다음이 있는지 안다. {@code size} 건만 읽으면 남은 행이 딱 {@code size} 건일 때
     * 다음 커서를 주고, 받는 쪽은 빈 페이지를 한 번 더 부르게 된다.
     *
     * @param cursor 이 키보다 큰 행부터. {@code null} 이면 처음부터
     * @param size   한 페이지 건수. 범위는 컨트롤러가 검사한다
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public RegistrationPage findPage(String cursor, int size) {
        List<Registration> rows = repository.findByExternalKeyGreaterThanOrderByExternalKeyAsc(
                cursor == null ? "" : cursor, Limit.of(size + 1));
        if (rows.size() <= size) {
            return new RegistrationPage(List.copyOf(rows), null);
        }
        List<Registration> page = List.copyOf(rows.subList(0, size));
        return new RegistrationPage(page, page.getLast().externalKey());
    }
}
