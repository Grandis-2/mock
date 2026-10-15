-- 외부 예약 Mock 스키마
-- 기준: ERD v14.1 (2026-10-06) 의 external_mock 영역. 칸 · 제약은 v5(2026-09-17)와 같고,
-- customer_id · product_id 만 be 의 id UUID 전환(NV-326)을 따라 BINARY(16) 이다(ERD 는 BIGINT).
-- 등록 요청 본문은 본 서비스(preorder) 형식이고, 이 표에는 ERD 칸으로 바꿔 넣는다(docs/api.md 「내용 비교」).
-- 럭키드로우 표 둘(draws · draw_entries, NV-505)은 Mock 이 먼저 정했다 — ERD 에는 머지 뒤 반영을 부탁한다.
-- 운영 DB 의 표는 db/migrations(Flyway)가 정한다 — 정본은 그쪽이다. 이 파일은 같은 표의 마지막 모습을 한 파일로 둔 것이고
-- 로컬 compose 와 MySQL 시험(Testcontainers)이 쓴다. 둘을 맞춰 보는 검사는 없으니 표를 바꾸면 새 마이그레이션과 이 파일을 함께 고친다.
-- Hibernate 가 테이블을 만들지 않게 ddl-auto 는 validate 로 둔다.
-- 표마다 CREATE TABLE IF NOT EXISTS 라 이미 있는 DB 에 다시 돌려도 없는 표만 생긴다.

CREATE DATABASE IF NOT EXISTS external_mock
    DEFAULT CHARACTER SET utf8mb4;

USE external_mock;

-- 키 저장과 등록 원장을 합친 표.
-- 미등록 취소는 키 / 상태 / 취소 시각만 저장한다.
-- 모든 등록·취소는 같은 키 행의 생성·잠금으로 직렬화하고, 취소된 행은 재활성화하지 않는다.
-- 일시 실패는 성공 등록으로 저장하지 않는다.
CREATE TABLE IF NOT EXISTS preorder_registrations
(
    -- 본 서비스 preorders.preorder_token 을 그대로 키로 쓴다. 대소문자를 구별한다.
    external_key    VARCHAR(100) COLLATE utf8mb4_bin NOT NULL COMMENT '우리 preorders.preorder_token',
    -- Mock 이 최초 등록에서 발급한다. 등록 전 취소 표식이면 NULL.
    external_number VARCHAR(100) COLLATE utf8mb4_bin NULL COMMENT '외부 예약번호',
    -- 같은 키 재등록의 내용 비교 대상 세 칸. 요청의 customerRef · itemCode · optionCode 를 바꿔 넣는다.
    -- id 두 칸은 UUID 16바이트로, 순서는 UUID_TO_BIN(u) 와 같다. 사람이 읽을 때는 BIN_TO_UUID(customer_id).
    customer_id     BINARY(16)                       NULL COMMENT '우리 customers.id (UUID)',
    product_id      BINARY(16)                       NULL COMMENT '우리 products.id (UUID)',
    sku             VARCHAR(80) COLLATE utf8mb4_bin  NULL COMMENT '우리 product_options.sku',
    status          VARCHAR(20)                      NOT NULL COMMENT 'ACTIVE / CANCELED',
    confirmed_at    DATETIME(6)                      NULL COMMENT '등록을 확정한 시각',
    canceled_at     DATETIME(6)                      NULL COMMENT '취소 표식을 남긴 시각',

    PRIMARY KEY (external_key),
    UNIQUE KEY uq_registration_number (external_number),

    CONSTRAINT ck_registration_status CHECK (status IN ('ACTIVE', 'CANCELED')),
    CONSTRAINT ck_registration_active_fields CHECK (
        status <> 'ACTIVE' OR (external_number IS NOT NULL
            AND customer_id IS NOT NULL
            AND product_id IS NOT NULL
            AND sku IS NOT NULL
            AND confirmed_at IS NOT NULL)),
    CONSTRAINT ck_registration_canceled CHECK (status <> 'CANCELED' OR canceled_at IS NOT NULL)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='외부 예약 Mock 등록 원장';

-- 럭키드로우 회차. Mock 은 회차를 미리 모른다 — 처음 보는 회차 id 로 응모가 오면 이 행을 만든다.
-- 응모와 추첨은 이 행의 잠금으로 줄을 세운다. 추첨 중에 응모가 끼어들어 센 수와 뽑은 대상이 달라지지 않게.
CREATE TABLE IF NOT EXISTS draws
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
CREATE TABLE IF NOT EXISTS draw_entries
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
