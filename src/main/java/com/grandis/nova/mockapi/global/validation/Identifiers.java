package com.grandis.nova.mockapi.global.validation;

import com.grandis.nova.mockapi.global.error.ErrorCode;
import com.grandis.nova.mockapi.global.error.MockException;
import java.util.regex.Pattern;

/**
 * 외부 키 · 외부 번호의 형식 규칙. 영문 · 숫자 · {@code . _ -} 로 된 1~100자다(ERD 의 두 칸이 {@code varchar(100)}).
 * 본 서비스의 {@code preorder_token}(UUID)과 Mock 이 발급한 번호({@code R-yyyyMMdd-NNNNNNNNNN})가 모두 들어간다.
 *
 * <p>문자를 좁히는 이유 — 받아 놓고 다시 찾을 수 없는 키를 만들지 않는다.
 * <ul>
 *   <li>{@code /} — 키 조회는 키를 경로에 넣는다. 등록은 되는데 키 조회로는 찾을 수 없다</li>
 *   <li>공백 — 키 칸 콜레이션 {@code utf8mb4_bin} 은 끝 공백을 무시하고 비교한다(PAD SPACE).
 *       {@code pad-1 } 로 등록하면 {@code pad-1} 의 조회 · 취소가 그 등록을 잡는다</li>
 *   <li>비 ASCII — 헤더는 ISO-8859-1 로 읽혀 깨진 채 저장된다</li>
 *   <li>{@code ,} — {@code Idempotency-Key} 헤더가 두 개면 {@code a,b} 한 키로 합쳐진다</li>
 *   <li>{@code .} · {@code ..} 만으로 된 키 — 경로에서 현재 · 상위 경로로 해석된다</li>
 * </ul>
 *
 * <p>쓰는 곳마다 애너테이션 대신 이걸 쓰는 이유가 다르다.
 * <ul>
 *   <li>등록의 {@code Idempotency-Key} 헤더 · 키 조회의 경로 변수 — 헤더 · 경로 변수에 검증 애너테이션을
 *       붙이면 스프링이 본문 검증까지 메서드 검증으로 바꿔, 오류 메시지에 필드 이름 대신 매개변수 이름이 나간다</li>
 *   <li>취소 본문 — 본문 record 는 애너테이션으로도 필드 이름이 제대로 나온다. 다만 "키와 번호 중 하나 이상"
 *       은 애너테이션으로 쓸 수 없어 어차피 컨트롤러에서 검사하고, 형식 규칙도 등록 키와 한 곳에서 맞춘다</li>
 * </ul>
 *
 * <p>{@code global} 에 두는 이유 — 등록 파트(등록 · 조회 · 취소)와 제어 파트(결함을 거는 키)가 같은 키를 다룬다.
 * 규칙을 두 곳에 두면 한쪽만 바뀌어, 결함은 걸리는데 그 키로 등록이 안 되는 식으로 어긋난다.
 */
public final class Identifiers {

    public static final int MAX_LENGTH = 100;

    private static final Pattern FORMAT = Pattern.compile("(?!\\.{1,2}$)[A-Za-z0-9._-]{1," + MAX_LENGTH + "}");

    private Identifiers() {
    }

    /**
     * @param name 오류 메시지에 쓸 이름. API 의 필드 · 헤더 이름이다
     * @throws MockException 형식이 맞지 않으면 400
     */
    public static void requireFormat(String name, String value) {
        if (!FORMAT.matcher(value).matches()) {
            throw new MockException(ErrorCode.INVALID_REQUEST,
                    name + " 은(는) 영문 · 숫자 · . _ - 로 된 1~" + MAX_LENGTH + "자여야 합니다.");
        }
    }
}
