-- 외부 예약 Mock 스키마
-- 기준: ERD (2026-10-06 갱신) 의 external_mock 영역. v5(2026-09-17)와 같다
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
    -- 같은 키 재등록의 내용 비교 대상 세 칸.
    customer_id     BIGINT                           NULL COMMENT '우리 customers.id',
    product_id      BIGINT                           NULL COMMENT '우리 products.id',
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
