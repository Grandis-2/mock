-- 외부 예약 Mock 스키마
-- 기준: preorder 의 등록 요청 본문(RegisterRequestPayload). be 결정(2026-10-06)으로 ERD 보다 이쪽을 따른다.
-- ERD 의 external_mock 영역은 아직 옛 칸(customer_id · product_id · sku)이다 — ERD 를 갱신할 때 이 파일에 맞춘다.
-- 이 파일이 스키마의 정본이다. Hibernate 가 테이블을 만들지 않게 ddl-auto 는 validate 로 둔다.

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
    -- 같은 키 재등록의 내용 비교 대상 다섯 칸. 이름은 preorder 요청 본문과 같다.
    -- 본문의 ourReservationId 는 external_key 와 같은 값이라 따로 두지 않는다.
    -- 참조 · 코드는 숫자로 바꾸지 않고 받은 문자열 그대로 둔다 — 형식은 본 서비스가 정한다.
    customer_ref    VARCHAR(100) COLLATE utf8mb4_bin NULL COMMENT '고객 참조. 지금은 customers.id 문자열',
    item_code       VARCHAR(100) COLLATE utf8mb4_bin NULL COMMENT '상품 코드. 지금은 products.id 문자열',
    option_code     VARCHAR(80) COLLATE utf8mb4_bin  NULL COMMENT '옵션 코드. product_options.sku',
    qty             INT                              NULL COMMENT '수량. 지금은 항상 1',
    scope           VARCHAR(50) COLLATE utf8mb4_bin  NULL COMMENT '요청 범위. 지금은 preorder',
    status          VARCHAR(20)                      NOT NULL COMMENT 'ACTIVE / CANCELED',
    confirmed_at    DATETIME(6)                      NULL COMMENT '등록을 확정한 시각',
    canceled_at     DATETIME(6)                      NULL COMMENT '취소 표식을 남긴 시각',

    PRIMARY KEY (external_key),
    UNIQUE KEY uq_registration_number (external_number),

    CONSTRAINT ck_registration_status CHECK (status IN ('ACTIVE', 'CANCELED')),
    CONSTRAINT ck_registration_active_fields CHECK (
        status <> 'ACTIVE' OR (external_number IS NOT NULL
            AND customer_ref IS NOT NULL
            AND item_code IS NOT NULL
            AND option_code IS NOT NULL
            AND qty IS NOT NULL
            AND scope IS NOT NULL
            AND confirmed_at IS NOT NULL)),
    CONSTRAINT ck_registration_qty CHECK (qty IS NULL OR qty >= 1),
    CONSTRAINT ck_registration_canceled CHECK (status <> 'CANCELED' OR canceled_at IS NOT NULL)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='외부 예약 Mock 등록 원장';
