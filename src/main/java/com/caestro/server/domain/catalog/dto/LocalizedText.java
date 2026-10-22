package com.caestro.server.domain.catalog.dto;

/** ko/en 한 쌍의 문구. 응답에서는 en 이 비어 있으면 ko 를 복사해 내려준다 — 클라이언트가 빈 문자열을 걱정하지 않게. */
public record LocalizedText(String ko, String en) {

    public static LocalizedText of(String ko, String en) {
        if (ko == null) {
            return null;
        }
        return new LocalizedText(ko, (en == null || en.isBlank()) ? ko : en);
    }
}
