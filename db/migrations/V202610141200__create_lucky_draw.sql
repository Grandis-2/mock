-- 럭키드로우 회차 · 응모 표 (NV-505). docs/schema.sql 에 더해진 두 표와 같은 정의다.
-- IF NOT EXISTS 를 쓰지 않는다. 옛 정의의 표가 남아 있으면 조용히 건너뛰지 않고 여기서 실패해야 한다.

-- 럭키드로우 회차. Mock 은 회차를 미리 모른다 — 처음 보는 회차 id 로 응모가 오면 이 행을 만든다.
-- 응모와 추첨은 이 행의 잠금으로 줄을 세운다. 추첨 중에 응모가 끼어들어 센 수와 뽑은 대상이 달라지지 않게.
CREATE TABLE draws
(
    -- 본 서비스 draw_campaigns.id 를 그대로 키로 쓴다. 추첨 요청의 멱등 키이기도 하다.
    draw_id              VARCHAR(100) COLLATE utf8mb4_bin NOT NULL COMMENT '우리 draw_campaigns.id',
    status               VARCHAR(20)                      NOT NULL COMMENT 'OPEN / DRAWN',
    -- 추첨 요청 두 칸. 같은 회차를 다시 추첨하라고 하면 이 둘과 비교해 재생하거나 거절한다.
    winner_count         INT                              NULL COMMENT '당첨 인원 (DRAWN 일 때만)',
    expected_entry_count INT                              NULL COMMENT '추첨 요청의 기대 응모 수 (보내지 않았으면 NULL)',
    drawn_at             DATETIME(6)                      NULL COMMENT '추첨한 시각',
    created_at           DATETIME(6)                      NOT NULL COMMENT '첫 응모로 회차를 만든 시각',

    PRIMARY KEY (draw_id),

    CONSTRAINT ck_draw_status CHECK (status IN ('OPEN', 'DRAWN')),
    CONSTRAINT ck_draw_drawn_fields CHECK (
        status <> 'DRAWN' OR (winner_count IS NOT NULL AND drawn_at IS NOT NULL)),
    CONSTRAINT ck_draw_winner_count CHECK (winner_count IS NULL OR winner_count >= 1)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='외부 예약 Mock 럭키드로우 회차';

-- 럭키드로우 응모. 한 건씩 쌓이고, 추첨하면 당첨된 행에 뽑힌 순서를 적는다.
CREATE TABLE draw_entries
(
    -- 본 서비스 draw_entries.id 를 그대로 키로 쓴다(Idempotency-Key). 같은 응모가 다시 오면 이 행으로 재생한다.
    entry_id    VARCHAR(100) COLLATE utf8mb4_bin NOT NULL COMMENT '우리 draw_entries.id',
    draw_id     VARCHAR(100) COLLATE utf8mb4_bin NOT NULL COMMENT '회차 id',
    customer_id BINARY(16)                       NOT NULL COMMENT '우리 customers.id (UUID)',
    -- 당첨이면 뽑힌 순서(1부터). 낙첨 · 추첨 전은 NULL.
    winner_rank INT                              NULL COMMENT '당첨 순서',
    received_at DATETIME(6)                      NOT NULL COMMENT '응모를 받은 시각',

    PRIMARY KEY (entry_id),
    -- 회차당 회원 하나. be 의 uq_draw_entry_customer 와 같은 규칙이다 — 어기면 be 버그라 409 로 알린다.
    UNIQUE KEY uq_draw_entry_customer (draw_id, customer_id),
    -- 회차의 응모 수 세기 · 당첨자 읽기.
    KEY ix_draw_entry_winner (draw_id, winner_rank),

    CONSTRAINT ck_draw_entry_rank CHECK (winner_rank IS NULL OR winner_rank >= 1)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='외부 예약 Mock 럭키드로우 응모';
