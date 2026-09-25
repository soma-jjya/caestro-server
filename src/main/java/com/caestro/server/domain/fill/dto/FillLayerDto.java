package com.caestro.server.domain.fill.dto;

/** 프레임 요소 하나: 중심(cx,cy)과 너비(w)는 프레임 크기 대비 비율, rot는 시계 방향 각도. */
public record FillLayerDto(float cx, float cy, float w, float rot, String url) {
    public FillLayerDto withUrl(String newUrl) {
        return new FillLayerDto(cx, cy, w, rot, newUrl);
    }
}
