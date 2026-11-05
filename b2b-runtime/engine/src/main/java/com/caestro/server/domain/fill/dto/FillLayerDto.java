package com.caestro.server.domain.fill.dto;

/**
 * 프레임 요소 하나: 중심(cx,cy)과 너비(w)는 프레임 크기 대비 비율, rot는 시계 방향 각도.
 * cell 은 요소가 들어 있는 칸(0부터)으로, 그 칸 밖으로 나가는 부분은 잘려 그려진다; null 이면 프레임 위에 그대로 얹힌다.
 * 서버는 저장만 하고 그리지 않으므로 값은 그대로 돌려준다.
 */
public record FillLayerDto(float cx, float cy, float w, float rot, String url, Integer cell) {
    public FillLayerDto withUrl(String newUrl) {
        return new FillLayerDto(cx, cy, w, rot, newUrl, cell);
    }
}
