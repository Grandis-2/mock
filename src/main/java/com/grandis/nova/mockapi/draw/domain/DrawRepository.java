package com.grandis.nova.mockapi.draw.domain;

import org.springframework.data.jpa.repository.JpaRepository;

/** 회차 저장소. 잠금 조회는 응모 · 추첨을 만들 때 더한다. */
public interface DrawRepository extends JpaRepository<Draw, String> {
}
