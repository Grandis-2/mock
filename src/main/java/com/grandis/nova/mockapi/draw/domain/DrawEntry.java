package com.grandis.nova.mockapi.draw.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * 럭키드로우 응모. 응모 id 하나에 행 하나다. 한 건씩 쌓이고, 추첨하면 당첨된 행에 뽑힌 순서가 적힌다.
 *
 * <p>스키마는 {@code docs/schema.sql} 이 정본이다. 회차당 회원 하나 제약은 정본에도 있고, 여기 적어두는 이유는 H2 시험에도
 * 같은 제약이 생기게 하려는 것이다.
 *
 * <p>회차({@link Draw})를 객체로 잇지 않고 id 칸으로만 둔다. 응모는 늘 회차 행을 먼저 잠근 뒤에 다루므로 객체로 이어 둘
 * 이유가 없고, 이으면 응모를 읽을 때마다 회차를 함께 읽는다.
 */
@Entity
@Table(name = "draw_entries",
        uniqueConstraints = @UniqueConstraint(name = "uq_draw_entry_customer", columnNames = {"draw_id", "customer_id"}))
public class DrawEntry implements Persistable<String> {

    /** 본 서비스 draw_entries.id 를 그대로 쓴다(Idempotency-Key). */
    @Id
    @Column(name = "entry_id", length = 100, nullable = false, updatable = false)
    private String entryId;

    @Column(name = "draw_id", length = 100, nullable = false, updatable = false)
    private String drawId;

    // BINARY(16). 등록 원장의 customer_id 와 같은 매핑이다(docs/schema.sql).
    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    /** 당첨이면 뽑힌 순서(1부터). 낙첨 · 추첨 전은 null. */
    @Column(name = "winner_rank")
    private Integer winnerRank;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    /** 새로 만든 객체인지. {@link Draw#isNew()} 와 같은 이유다 — 같은 새 응모가 동시에 오면 중복 키(1062)로 떨어져야 한다. */
    @Transient
    private boolean isNew = true;

    protected DrawEntry() {
    }

    private DrawEntry(String entryId, String drawId, UUID customerId, Instant receivedAt) {
        this.entryId = entryId;
        this.drawId = drawId;
        this.customerId = customerId;
        this.receivedAt = receivedAt;
    }

    /** 새로 받은 응모. 추첨 전이라 당첨 순서가 없다. */
    public static DrawEntry received(String entryId, String drawId, UUID customerId, Instant receivedAt) {
        return new DrawEntry(entryId, drawId, customerId, receivedAt);
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public String getId() {
        return entryId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public String entryId() {
        return entryId;
    }

    public String drawId() {
        return drawId;
    }

    public UUID customerId() {
        return customerId;
    }

    public Integer winnerRank() {
        return winnerRank;
    }

    public Instant receivedAt() {
        return receivedAt;
    }
}
