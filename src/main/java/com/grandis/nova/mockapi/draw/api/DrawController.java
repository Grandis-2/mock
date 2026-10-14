package com.grandis.nova.mockapi.draw.api;

import com.grandis.nova.mockapi.draw.application.DrawReader;
import com.grandis.nova.mockapi.global.validation.Identifiers;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 럭키드로우 회차 조회 · 추첨. */
@RestController
@RequestMapping(path = "/external/draws", produces = MediaType.APPLICATION_JSON_VALUE)
public class DrawController {

    private final DrawReader reader;

    public DrawController(DrawReader reader) {
        this.reader = reader;
    }

    /**
     * 회차 조회. 받은 응모 수와 추첨 결과를 준다. 404 는 "이 회차로 받은 응모가 아직 없다" 는 뜻이다(응모 수 0).
     *
     * <p>형식이 틀린 회차 id 는 400 이다. 조회까지 보내면 404 로 나가 "아직 응모가 없다" 와 뜻이 섞인다.
     */
    @GetMapping("/{drawId}")
    public DrawResponse find(@PathVariable String drawId) {
        Identifiers.requireFormat("drawId", drawId);
        return DrawResponse.from(reader.find(drawId));
    }
}
