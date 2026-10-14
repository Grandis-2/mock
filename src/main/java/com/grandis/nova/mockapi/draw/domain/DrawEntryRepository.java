package com.grandis.nova.mockapi.draw.domain;

import org.springframework.data.jpa.repository.JpaRepository;

/** 응모 저장소. 세기 · 찾기는 응모 · 회차 조회 · 추첨을 만들 때 더한다. */
public interface DrawEntryRepository extends JpaRepository<DrawEntry, String> {
}
