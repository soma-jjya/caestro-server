package com.caestro.server.domain.dailylimit.enums;

/**
 * 무료 사용자에게 하루 횟수 제한이 있는 동작. 한도는 설정(limits.*)에서 읽는다.
 */
public enum DailyAction {

    /** 완성한 네컷을 앨범에 처음 저장하기 (같은 네컷을 다시 저장해도 한 번) */
    FOURCUT_SAVE
}
