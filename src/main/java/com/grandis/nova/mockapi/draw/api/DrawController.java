package com.grandis.nova.mockapi.draw.api;

import com.grandis.nova.mockapi.draw.application.DrawReader;
import com.grandis.nova.mockapi.draw.application.DrawResult;
import com.grandis.nova.mockapi.draw.application.DrawService;
import com.grandis.nova.mockapi.global.validation.Identifiers;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 럭키드로우 회차 조회 · 추첨.
 *
 * <p>추첨도 {@code consumes} 를 매핑에 둔다 — 응모와 같은 이유로, 헤더가 틀린 요청을 추첨한 뒤가 아니라 처리 전에 거른다.
 */
@RestController
@RequestMapping(path = "/external/draws", produces = MediaType.APPLICATION_JSON_VALUE)
public class DrawController {

    private final DrawReader reader;
    private final DrawService service;

    public DrawController(DrawReader reader, DrawService service) {
        this.reader = reader;
        this.service = service;
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

    /**
     * 추첨 (멱등 — 회차 id 가 키다). 같은 요청이 다시 오면 다시 뽑지 않고 처음 결과를 200 으로 재생한다. 응답은 회차 조회와 같은
     * 모양이고, worker 는 {@code winnerEntryIds} 를 그대로 order 에 넘긴다(DRAW_RESULT_READY).
     */
    @PostMapping(path = "/{drawId}/draw", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DrawResponse> draw(@PathVariable String drawId, @Valid @RequestBody DrawRequest request) {
        Identifiers.requireFormat("drawId", drawId);

        DrawResult result = service.draw(drawId, request.toCommand());

        return ResponseEntity.ok()
                .header(DrawEntryController.IDEMPOTENT_REPLAY, String.valueOf(result.replayed()))
                .header(DrawEntryController.CONFIG_VERSION, String.valueOf(result.configVersion()))
                .body(DrawResponse.from(result.state()));
    }
}
