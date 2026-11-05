package com.caestro.server.domain.catalog.dto;

/**
 * 사진이 들어갈 창 하나: 이미지 비율(0..1)의 회전 전 사각형과 중심 기준 시계방향 회전각(°, -180..180).
 * 앱·웹은 사진을 이 사각형에 cover 로 맞춘 뒤 중심을 축으로 돌려 그린다.
 */
public record FrameWindowDto(double x, double y, double w, double h, double rot) {
}
