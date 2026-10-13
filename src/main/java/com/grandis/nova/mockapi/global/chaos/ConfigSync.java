package com.grandis.nova.mockapi.global.chaos;

import java.time.Instant;
import java.util.function.Consumer;

/**
 * 설정을 서버끼리 나누는 길. Mock 을 여러 대로 띄울 때만 있다({@code mock.shared-state=redis}).
 *
 * <p>한 대면 이 길이 없고 {@link MockConfigStore} 가 메모리에서 혼자 버전을 올린다. 여러 대면 버전을 공유 저장소가
 * 정해야 한다 — 서버마다 따로 올리면 두 서버가 같은 버전에 다른 설정을 갖게 되고, 응답 헤더
 * {@code X-Mock-Config-Version} 으로 어느 설정이 적용됐는지 가릴 수 없다.
 */
public interface ConfigSync {

    /**
     * 기동할 때 한 번 부른다. 공유된 설정이 있으면 그것을, 없으면 {@code defaults} 를 올리고 그것을 돌려준다.
     * 한 대가 재기동해도 다른 서버들의 설정을 기본값으로 덮지 않기 위해서다.
     *
     * @param onChange 다른 서버가 설정을 바꿨을 때 받을 곳
     */
    MockConfigStore.Applied start(MockConfigStore.Applied defaults, Consumer<MockConfigStore.Applied> onChange);

    /**
     * 설정을 바꾸고 다른 서버들에 알린다. {@code next} 의 버전은 보지 않는다 — 공유 저장소가 하나씩 올려 정한다.
     *
     * @return 저장된 설정(정해진 버전 포함)
     */
    MockConfigStore.Applied publish(ConfigSnapshot next, Instant appliedAt);
}
