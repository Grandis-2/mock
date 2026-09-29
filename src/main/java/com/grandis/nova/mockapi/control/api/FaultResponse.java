package com.grandis.nova.mockapi.control.api;

import com.grandis.nova.mockapi.global.chaos.FaultType;
import java.time.Instant;

/** 결함 주입의 응답. 무엇을 어느 키에 걸었는지 되돌려 준다. */
public record FaultResponse(
        String externalKey,
        FaultType faultType,
        Instant createdAt
) {
}
