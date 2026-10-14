package com.grandis.nova.mockapi.draw.domain;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.random.RandomGenerator;
import org.springframework.stereotype.Component;

/**
 * 응모 중에서 당첨을 뽑는다. 겹치지 않고, 모든 응모가 같은 확률이다.
 *
 * <p>부분 Fisher–Yates 셔플이다 — 남은 후보 중 하나를 고르게 골라 앞으로 빼는 일을 당첨 인원만큼 한다. 전부 섞지 않고 필요한
 * 만큼만 섞으므로 후보가 많아도 당첨 인원만큼의 일이다.
 *
 * <p>{@link SecureRandom} 을 쓴다. 예약번호({@code ExternalNumberGenerator})는 겹치지만 않으면 되지만 추첨은 결과를 미리 알 수
 * 없어야 한다 — 시연에서도 "공정한 추첨" 을 보여 주는 자리다. 여러 스레드가 함께 불러도 된다.
 */
@Component
public class WinnerPicker {

    private final RandomGenerator random;

    public WinnerPicker() {
        this(new SecureRandom());
    }

    /** 시험이 씨앗을 고정한 난수로 쓴다. */
    WinnerPicker(RandomGenerator random) {
        this.random = random;
    }

    /**
     * @param candidates 그 회차의 응모 id. 바꾸지 않는다
     * @param count      당첨 인원. 1 이상, 후보 수보다 작아야 한다 — 부르는 쪽이 먼저 거른다(422)
     * @return 당첨 응모 id, 뽑힌 순서
     */
    public List<String> pick(List<String> candidates, int count) {
        if (count < 1 || count >= candidates.size()) {
            throw new IllegalArgumentException(
                    "당첨 인원은 1 이상, 후보 수보다 작아야 합니다. count=" + count + ", candidates=" + candidates.size());
        }
        List<String> pool = new ArrayList<>(candidates);
        for (int i = 0; i < count; i++) {
            Collections.swap(pool, i, i + random.nextInt(pool.size() - i));
        }
        return List.copyOf(pool.subList(0, count));
    }
}
