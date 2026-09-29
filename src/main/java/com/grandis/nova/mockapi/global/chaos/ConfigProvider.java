package com.grandis.nova.mockapi.global.chaos;

/**
 * 현재 지연·실패 설정을 알려준다. 등록 처리가 시작할 때 한 번 부른다.
 *
 * <p>구현은 제어 파트가 맡는다. 등록 파트는 이 인터페이스만 보고 만든다.
 */
public interface ConfigProvider {

    ConfigSnapshot snapshot();
}
