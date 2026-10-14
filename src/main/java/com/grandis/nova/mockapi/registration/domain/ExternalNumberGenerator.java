package com.grandis.nova.mockapi.registration.domain;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

/**
 * 외부 예약번호를 만든다. {@code R-yyyyMMdd-NNNNNNNNNN} — UTC 발급일 + 10자리 난수.
 *
 * <p>카운터 대신 난수를 쓴다. 카운터는 재기동하면 시드를 다시 잡아야 하고 자정에 되돌려야 하며,
 * Mock 이 한 프로세스라는 전제에 기댄다. 난수는 그런 게 없고, 겹치면 번호 유니크 제약이 중복 키로
 * 떨어뜨려 등록 재시도 루프가 새 번호로 다시 시도한다.
 *
 * <p>10자리인 이유 — 6자리면 하루 100만 개라 부하 시험에서 동난다(하루 18만 건이 쌓이면 새 번호 하나가
 * 기존 번호와 겹칠 확률이 18%). Mock 안에서 겹치는 것은 번호 유니크 제약과 재시도가 막는다.
 *
 * <p>막지 못하는 것은 {@code reset} 뒤다. Mock 만 지우면 전에 준 번호가 다시 나올 수 있는데, 본 서비스는 받은
 * 번호를 UNIQUE 로 저장하므로 그쪽 저장이 깨진다. 이 확률은 번호 하나 기준(18만 건이면 0.002%)이 아니라
 * 실행 전체로 따져야 한다 — 본 서비스에 남은 번호 수 × 새로 낼 번호 수 ÷ 10¹⁰ 만큼 겹칠 것으로 기대되고,
 * 실행을 거듭할수록 커진다. 같은 날 5,000건씩 14번 돌린 뒤 15번째 실행에서 하나라도 겹칠 확률은
 * 약 3.4% 다(1 − e^−(70,000 × 5,000 ÷ 10¹⁰)). 그래서 반복 실행 때는 본 서비스 쪽 기록도 함께 비운다(명세 reset).
 *
 * <p><b>재시도할 때마다 새로 불러야 한다.</b> 한 번 뽑은 번호로 다시 시도하면 같은 자리에서 영원히 부딪힌다.
 */
@Component
public class ExternalNumberGenerator {

    private static final DateTimeFormatter ISSUE_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private static final long SEQUENCE_BOUND = 10_000_000_000L;

    public String next(Instant now) {
        long sequence = ThreadLocalRandom.current().nextLong(SEQUENCE_BOUND);
        return "R-" + ISSUE_DATE.format(now) + "-" + String.format("%010d", sequence);
    }
}
