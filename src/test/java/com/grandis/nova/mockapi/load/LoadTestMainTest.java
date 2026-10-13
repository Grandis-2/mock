package com.grandis.nova.mockapi.load;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 부하 실행기가 Mock 설정 응답을 읽는 부분. 판정 규칙이 모드마다 달라 모드를 잘못 읽으면 판정이 통째로 틀린다. */
class LoadTestMainTest {

    /** 예전에는 "TIMEOUT 이 아니면 HTTP_5XX" 로 읽어 MIXED 가 HTTP_5XX 로 둔갑했다(NV-312). */
    @Test
    @DisplayName("실패 모드는 설정 응답의 이름 그대로 읽는다 — 없으면 HTTP_5XX")
    void readsFailureModeByName() {
        assertThat(LoadTestMain.readFailureMode("{\"failureRate\":0.05,\"failureMode\":\"MIXED\",\"mixedResponses\":[]}"))
                .isEqualTo("MIXED");
        assertThat(LoadTestMain.readFailureMode("{\"failureMode\" : \"TIMEOUT\"}")).isEqualTo("TIMEOUT");
        assertThat(LoadTestMain.readFailureMode("{\"failureMode\":\"HTTP_5XX\"}")).isEqualTo("HTTP_5XX");
        assertThat(LoadTestMain.readFailureMode("{}")).isEqualTo("HTTP_5XX");
    }
}
