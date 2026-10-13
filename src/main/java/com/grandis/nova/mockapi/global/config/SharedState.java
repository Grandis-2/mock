package com.grandis.nova.mockapi.global.config;

/**
 * 설정(지연 · 실패율)과 결함을 어디에 두는가.
 *
 * <p>Mock 을 한 대로 띄우면 메모리로 충분하다. 여러 대로 띄우면 요청이 서버마다 나뉘어 가므로, 한 대에서 바꾼
 * 실패율이나 건 결함이 다른 서버에는 없다. 그때는 Redis 에 두고 서버끼리 나눈다.
 */
public enum SharedState {

    /** 서버 메모리. 한 대일 때의 기본값이다. 재기동하면 설정이 기본값으로 돌아간다. */
    MEMORY,

    /** Redis. 여러 대일 때 쓴다. 한 대에서 바꾸면 모두 바뀌고, 재기동해도 설정이 남는다. */
    REDIS
}
