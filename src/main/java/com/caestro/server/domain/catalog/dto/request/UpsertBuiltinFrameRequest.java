package com.caestro.server.domain.catalog.dto.request;

import com.caestro.server.domain.catalog.dto.FrameWindowDto;
import com.caestro.server.domain.catalog.dto.LocalizedText;
import java.util.List;

/**
 * 기본 프레임 덮어쓰기(카탈로그 5차). 보낸 필드만 바뀐다(null 은 그대로).
 *
 * @param title        이름. 한국어를 비워 보내면 앱에 든 이름으로 돌아간다
 * @param subtitle     한 줄 소개. 한국어를 비워 보내면 앱에 든 문구로
 * @param pro          Pro 전용인가
 * @param badge        제목 왼쪽 배지. 한국어를 비워 보내면 지운다
 * @param hidden       앱 목록에서 숨기기
 * @param imageId      바꿀 그림(AI 프레임만, windows 와 함께)
 * @param windows      창 4개(AI 프레임만). imageId 없이 보내면 이미 바꾼 그림의 창만 고친다
 * @param resetArtwork true 면 앱에 든 그림·창으로 돌아간다
 */
public record UpsertBuiltinFrameRequest(
        LocalizedText title,
        LocalizedText subtitle,
        Boolean pro,
        LocalizedText badge,
        Boolean hidden,
        String imageId,
        List<FrameWindowDto> windows,
        Boolean resetArtwork
) {
}
