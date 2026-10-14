package com.grandis.nova.mockapi.draw.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 당첨 뽑기. 스프링 없이 본다. */
class WinnerPickerTest {

    private static final List<String> FIVE = List.of("e1", "e2", "e3", "e4", "e5");

    @Test
    @DisplayName("당첨 인원만큼, 겹치지 않게, 후보 안에서 뽑는다")
    void picksDistinctFromCandidates() {
        WinnerPicker picker = new WinnerPicker();
        List<String> candidates = IntStream.range(0, 1000).mapToObj(i -> "entry-" + i).toList();

        for (int round = 0; round < 100; round++) {
            List<String> winners = picker.pick(candidates, 10);

            assertThat(winners).as("round %d", round).hasSize(10).doesNotHaveDuplicates();
            assertThat(candidates).as("round %d", round).containsAll(winners);
        }
    }

    @Test
    @DisplayName("후보 목록은 바꾸지 않는다")
    void leavesCandidatesUntouched() {
        List<String> candidates = new ArrayList<>(FIVE);

        new WinnerPicker().pick(candidates, 3);

        assertThat(candidates).containsExactlyElementsOf(FIVE);
    }

    /**
     * 모든 응모가 같은 확률인지. 5명 중 1명을 5만 번 뽑으면 각자 평균 1만 번이고 표준편차는 약 89 다. ±500(5.6 표준편차)
     * 밖이면 뽑기가 치우친 것으로 본다. 씨앗을 고정해 늘 같은 결과라 우연히 깨지지 않는다.
     */
    @Test
    @DisplayName("모든 응모가 같은 확률로 뽑힌다 - 5명 중 1명 × 5만 번, 각자 1만 ± 500")
    void uniform() {
        WinnerPicker picker = new WinnerPicker(new SplittableRandom(20261014L));
        Map<String, Integer> wins = new HashMap<>();

        for (int i = 0; i < 50_000; i++) {
            wins.merge(picker.pick(FIVE, 1).getFirst(), 1, Integer::sum);
        }

        assertThat(wins).containsOnlyKeys(FIVE);
        assertThat(wins.values()).allSatisfy(count -> assertThat(count).isBetween(9_500, 10_500));
    }

    @Test
    @DisplayName("당첨 인원이 0 이하거나 후보 수 이상이면 부르는 쪽 버그 - 예외")
    void rejectsInvalidCount() {
        WinnerPicker picker = new WinnerPicker();

        assertThatThrownBy(() -> picker.pick(FIVE, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> picker.pick(FIVE, 5)).isInstanceOf(IllegalArgumentException.class);
    }
}
