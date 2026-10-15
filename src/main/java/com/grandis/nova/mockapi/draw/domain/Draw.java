package com.grandis.nova.mockapi.draw.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.data.domain.Persistable;

/**
 * 럭키드로우 회차. 회차 id 하나에 행 하나다.
 *
 * <p>스키마는 {@code db/migrations} 가 정본이다(로컬 · 시험은 {@code docs/schema.sql}). 칸을 바꾸면 새 마이그레이션을 더하고
 * schema.sql 도 함께 고친다.
 *
 * <p>Mock 은 회차를 미리 모른다 — 처음 보는 회차 id 로 응모가 오면 이 행을 만든다. 응모와 추첨은 이 행을 잠가 줄을
 * 세운다. 추첨 중에 응모가 끼어들면 센 수와 뽑은 대상이 달라진다.
 */
@Entity
@Table(name = "draws")
public class Draw implements Persistable<String> {

    /** 본 서비스 draw_campaigns.id 를 그대로 쓴다. 추첨 요청의 멱등 키이기도 하다. */
    @Id
    @Column(name = "draw_id", length = 100, nullable = false, updatable = false)
    private String drawId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private DrawStatus status;

    /** 당첨 인원. 추첨한 뒤에만 있다. */
    @Column(name = "winner_count")
    private Integer winnerCount;

    /** 추첨 요청에 실려 온 기대 응모 수. 보내지 않았으면 추첨 뒤에도 null 이다. */
    @Column(name = "expected_entry_count")
    private Integer expectedEntryCount;

    @Column(name = "drawn_at")
    private Instant drawnAt;

    /** 첫 응모로 회차를 만든 시각. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * 새로 만든 객체인지. 등록 원장({@code Registration})과 같은 이유다 — 키를 직접 정하는 엔티티라 알려주지 않으면
     * {@code save()} 가 INSERT 대신 merge 로 가서, 같은 새 회차를 동시에 만들 때 중복 키(1062)로 떨어지지 않는다.
     */
    @Transient
    private boolean isNew = true;

    protected Draw() {
    }

    private Draw(String drawId, Instant createdAt) {
        this.drawId = drawId;
        this.status = DrawStatus.OPEN;
        this.createdAt = createdAt;
    }

    /** 처음 보는 회차를 응모 받는 중으로 만든다. */
    public static Draw open(String drawId, Instant createdAt) {
        return new Draw(drawId, createdAt);
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public String getId() {
        return drawId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public boolean isDrawn() {
        return status == DrawStatus.DRAWN;
    }

    /** 추첨했다고 적는다. 요청 두 칸을 함께 남겨 같은 회차의 추첨 요청이 다시 오면 비교한다. 다시 열지 않는다. */
    public void markDrawn(int winnerCount, Integer expectedEntryCount, Instant drawnAt) {
        this.status = DrawStatus.DRAWN;
        this.winnerCount = winnerCount;
        this.expectedEntryCount = expectedEntryCount;
        this.drawnAt = drawnAt;
    }

    /**
     * 이미 추첨한 회차에 다시 온 추첨 요청과 저장된 요청이 다른 칸. 비어 있으면 같은 요청이다(재생).
     *
     * <p>회차 id 가 추첨의 멱등 키다. 기대 수도 비교한다 — 처음에 안 보내고 다시 보낼 때 보내면 다른 요청이다. 이름은 거절
     * 메시지에 그대로 쓰이므로 요청 필드 이름이다.
     */
    public List<String> differingFields(int winnerCount, Integer expectedEntryCount) {
        List<String> fields = new ArrayList<>(2);
        if (!Objects.equals(this.winnerCount, winnerCount)) {
            fields.add("winnerCount");
        }
        if (!Objects.equals(this.expectedEntryCount, expectedEntryCount)) {
            fields.add("expectedEntryCount");
        }
        return fields;
    }

    public String drawId() {
        return drawId;
    }

    public DrawStatus status() {
        return status;
    }

    public Integer winnerCount() {
        return winnerCount;
    }

    public Integer expectedEntryCount() {
        return expectedEntryCount;
    }

    public Instant drawnAt() {
        return drawnAt;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
