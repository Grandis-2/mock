package com.grandis.nova.mockapi.load;

import java.util.List;

/**
 * 한 실행의 판정. 보고서 맨 위의 한 줄과 하네스의 종료 코드가 여기서 나온다.
 *
 * <p>예전에는 판정 규칙이 문서(load-test.md)에만 있고 보고서는 숫자를 출력만 했다. 사람이 표를 읽고 판정해서,
 * 규칙 하나를 빠뜨려도 아무도 몰랐다(리뷰 H3 ③). 이제 규칙마다 통과 · 실패를 코드가 정한다.
 *
 * <p>결과는 셋이다. <b>판정 불가</b>는 FAIL 과 다르다 — Mock 이 틀린 게 아니라 이 실행이 판정할 자격이
 * 없다는 뜻이다(목표 부하를 못 만들었다, 엉뚱한 DB 를 읽었다). 요구사항 8장이 그런 실행을 합격으로 보지
 * 말라고 했고, 실패로 보면 Mock 탓이 된다.
 */
public record Verdict(Result result, List<Check> checks) {

    public enum Result {
        PASS(0, "PASS"),
        FAIL(1, "FAIL"),
        INVALID(2, "판정 불가");

        private final int exitCode;
        private final String label;

        Result(int exitCode, String label) {
            this.exitCode = exitCode;
            this.label = label;
        }

        /** 하네스의 종료 코드. 스크립트로 여러 번 돌릴 때 보고서를 열지 않고 거른다. */
        public int exitCode() {
            return exitCode;
        }

        public String label() {
            return label;
        }
    }

    /** 규칙의 종류. 어느 종류가 깨졌느냐로 결과가 갈린다. */
    public enum Kind {
        /** 이 실행이 판정할 자격이 있는가. 깨지면 판정 불가 */
        VALIDITY,
        /** Mock 이 계약대로 답했는가. 깨지면 FAIL */
        CONTRACT,
        /** Mock 이 충분히 빠른가. 깨지면 FAIL */
        METRIC
    }

    /**
     * @param name   규칙. 보고서에 그대로 찍는다
     * @param detail 실제로 본 값. 통과해도 적는다 — 기준에 얼마나 가까웠는지가 다음 판단의 근거다
     */
    public record Check(Kind kind, String name, boolean ok, String detail) {
    }

    /** 유효성이 하나라도 깨지면 판정 불가, 아니면 나머지가 하나라도 깨지면 FAIL. */
    public static Verdict of(List<Check> checks) {
        boolean invalid = checks.stream().anyMatch(c -> c.kind() == Kind.VALIDITY && !c.ok());
        boolean failed = checks.stream().anyMatch(c -> c.kind() != Kind.VALIDITY && !c.ok());
        Result result = invalid ? Result.INVALID : failed ? Result.FAIL : Result.PASS;
        return new Verdict(result, List.copyOf(checks));
    }
}
