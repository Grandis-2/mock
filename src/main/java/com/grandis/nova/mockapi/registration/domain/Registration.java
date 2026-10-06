package com.grandis.nova.mockapi.registration.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.data.domain.Persistable;

/**
 * 외부 예약 Mock 의 등록 원장. 키 하나에 행 하나다.
 *
 * <p>스키마는 {@code docs/schema.sql} 이 정본이다. 칸을 바꾸면 그쪽도 함께 고친다.
 * 번호 유니크 제약은 정본에도 있고, 여기 적어두는 이유는 H2 시험에도 같은 제약이 생기게 하려는 것이다.
 *
 * <p>시각 칸은 {@link Instant} 다. 시간대 없는 LocalDateTime 을 쓰면 Hibernate 의 {@code jdbc.time_zone}
 * 설정과 PC 시간대에 따라 DB 에 밀린 값이 들어간다. 정합성 검사가 이 표를 직접 읽으므로 저장된 값 자체가
 * UTC 여야 한다.
 */
@Entity
@Table(name = "preorder_registrations",
        uniqueConstraints = @UniqueConstraint(name = "uq_registration_number", columnNames = "external_number"))
public class Registration implements Persistable<String> {

    /** 본 서비스 preorders.preorder_token 을 그대로 쓴다. */
    @Id
    @Column(name = "external_key", length = 100, nullable = false, updatable = false)
    private String externalKey;

    /** Mock 이 최초 등록에서 발급한다. 등록 전 취소 표식이면 null. */
    @Column(name = "external_number", length = 100)
    private String externalNumber;

    /*
     * 신청 내용 다섯 칸. 이름은 본 서비스(preorder)의 등록 요청 본문과 같다.
     * 참조 · 코드는 숫자로 바꾸지 않고 받은 문자열 그대로 둔다 — 형식은 본 서비스가 정한다.
     */

    @Column(name = "customer_ref", length = 100)
    private String customerRef;

    @Column(name = "item_code", length = 100)
    private String itemCode;

    @Column(name = "option_code", length = 80)
    private String optionCode;

    @Column(name = "qty")
    private Integer qty;

    @Column(name = "scope", length = 50)
    private String scope;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private RegistrationStatus status;

    /** 등록을 확정한 시각. */
    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    /** 취소한 시각. 등록 전에 취소가 와도 이 값만 남는다. */
    @Column(name = "canceled_at")
    private Instant canceledAt;

    /**
     * 새로 만든 객체인지.
     *
     * <p>키를 직접 정하는 엔티티라 스프링 데이터가 키만 보고는 새것인지 알 수 없다. 모르면
     * {@code save()} 가 INSERT 대신 {@code merge}(조회 후 있으면 갱신)로 간다. 같은 새 키로 동시에
     * 들어온 요청이 둘 다 "없음" 을 본 뒤 merge 하면 뒤에 온 쪽이 앞의 등록을 덮어쓸 수 있다.
     * 새것이라고 알려주면 INSERT 로 가고, 겹치면 중복 키(1062)로 떨어져 재시도 경로를 탄다.
     */
    @Transient
    private boolean isNew = true;

    protected Registration() {
    }

    private Registration(String externalKey, String externalNumber, String customerRef, String itemCode,
                         String optionCode, Integer qty, String scope, RegistrationStatus status,
                         Instant confirmedAt, Instant canceledAt) {
        this.externalKey = externalKey;
        this.externalNumber = externalNumber;
        this.customerRef = customerRef;
        this.itemCode = itemCode;
        this.optionCode = optionCode;
        this.qty = qty;
        this.scope = scope;
        this.status = status;
        this.confirmedAt = confirmedAt;
        this.canceledAt = canceledAt;
    }

    /** 등록 성공 행. */
    public static Registration active(String externalKey, String externalNumber, String customerRef,
                                      String itemCode, String optionCode, int qty, String scope,
                                      Instant confirmedAt) {
        return new Registration(externalKey, externalNumber, customerRef, itemCode, optionCode, qty, scope,
                RegistrationStatus.ACTIVE, confirmedAt, null);
    }

    /** 등록 전 취소로 남기는 표식 행. 번호가 없다. */
    public static Registration cancelMarker(String externalKey, Instant canceledAt) {
        return new Registration(externalKey, null, null, null, null, null, null,
                RegistrationStatus.CANCELED, null, canceledAt);
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public String getId() {
        return externalKey;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public boolean isActive() {
        return status == RegistrationStatus.ACTIVE;
    }

    public boolean isCanceled() {
        return status == RegistrationStatus.CANCELED;
    }

    /**
     * 같은 키의 재요청과 저장된 내용이 다른 칸. 비어 있으면 같은 신청이다.
     *
     * <p>저장된 다섯 칸을 직접 비교한다. 해시로 비교하면 어느 칸이 달라 거절됐는지 알려줄 수 없다.
     * 이름은 응답 메시지에 그대로 쓰이므로 API 필드 이름이다.
     */
    public List<String> differingFields(String customerRef, String itemCode, String optionCode, int qty,
                                        String scope) {
        List<String> fields = new ArrayList<>(5);
        if (!Objects.equals(this.customerRef, customerRef)) {
            fields.add("customerRef");
        }
        if (!Objects.equals(this.itemCode, itemCode)) {
            fields.add("itemCode");
        }
        if (!Objects.equals(this.optionCode, optionCode)) {
            fields.add("optionCode");
        }
        if (!Objects.equals(this.qty, qty)) {
            fields.add("qty");
        }
        if (!Objects.equals(this.scope, scope)) {
            fields.add("scope");
        }
        return fields;
    }

    /** 취소한다. 이미 취소된 행은 그대로 둔다(재활성화·시각 덮어쓰기 없음). */
    public void cancel(Instant canceledAt) {
        if (isCanceled()) {
            return;
        }
        this.status = RegistrationStatus.CANCELED;
        this.canceledAt = canceledAt;
    }

    public String externalKey() {
        return externalKey;
    }

    public String externalNumber() {
        return externalNumber;
    }

    public String customerRef() {
        return customerRef;
    }

    public String itemCode() {
        return itemCode;
    }

    public String optionCode() {
        return optionCode;
    }

    public Integer qty() {
        return qty;
    }

    public String scope() {
        return scope;
    }

    public RegistrationStatus status() {
        return status;
    }

    public Instant confirmedAt() {
        return confirmedAt;
    }

    public Instant canceledAt() {
        return canceledAt;
    }
}
