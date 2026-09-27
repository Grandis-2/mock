package com.grandis.nova.mockapi.global.chaos;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 결함 보관소의 규칙을 본다. 스프링 없이 본다 — 동작이 맵 하나에 들어 있어 컨텍스트가 필요 없다.
 *
 * <p>여기서 보는 것은 <b>한 번만 발동한다</b>와 <b>키끼리 섞이지 않는다</b>다. 둘 다 틀려도 오류가
 * 나지 않고 시험이 조용히 잘못된 것을 증명하게 되는 종류다 — 결함이 두 번 발동하면 재시도마저
 * 응답을 잃어 "재생 확인" 이 영원히 안 되고, 키가 섞이면 결함을 걸지 않은 요청이 응답을 잃는다.
 */
class InMemoryFaultStoreTest {

    private static final String KEY = "test-lost-1";
    private static final String OTHER_KEY = "test-lost-2";

    private InMemoryFaultStore store;

    @BeforeEach
    void freshStore() {
        store = new InMemoryFaultStore();
    }

    @Test
    @DisplayName("걸지 않은 키는 발동하지 않는다")
    void notInjected() {
        assertThat(store.consumeResponseLost(KEY)).isFalse();
    }

    @Test
    @DisplayName("한 번 발동하면 자동으로 해제된다")
    void consumedOnce() {
        store.inject(KEY, FaultType.RESPONSE_LOST_AFTER_COMMIT);

        assertThat(store.consumeResponseLost(KEY)).isTrue();
        assertThat(store.consumeResponseLost(KEY)).isFalse();
    }

    @Test
    @DisplayName("다른 키에는 영향을 주지 않는다")
    void isolatedByKey() {
        store.inject(KEY, FaultType.RESPONSE_LOST_AFTER_COMMIT);

        assertThat(store.consumeResponseLost(OTHER_KEY)).isFalse();
        // 남의 키를 물어본 것이 내 결함을 소비하지 않았다
        assertThat(store.consumeResponseLost(KEY)).isTrue();
    }

    @Test
    @DisplayName("같은 키에 다시 걸면 새 결함으로 바뀌고, 그래도 한 번만 발동한다")
    void reinjectReplaces() {
        store.inject(KEY, FaultType.RESPONSE_LOST_AFTER_COMMIT);
        store.inject(KEY, FaultType.RESPONSE_LOST_AFTER_COMMIT);

        assertThat(store.consumeResponseLost(KEY)).isTrue();
        assertThat(store.consumeResponseLost(KEY)).isFalse();
    }

    @Test
    @DisplayName("초기화하면 발동하지 않은 결함이 사라진다")
    void clearDropsPendingFaults() {
        store.inject(KEY, FaultType.RESPONSE_LOST_AFTER_COMMIT);
        store.inject(OTHER_KEY, FaultType.RESPONSE_LOST_AFTER_COMMIT);

        store.clear();

        assertThat(store.consumeResponseLost(KEY)).isFalse();
        assertThat(store.consumeResponseLost(OTHER_KEY)).isFalse();
    }
}
