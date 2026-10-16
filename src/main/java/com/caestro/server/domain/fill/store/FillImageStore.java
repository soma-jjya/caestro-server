package com.caestro.server.domain.fill.store;

import com.caestro.server.domain.fill.entity.FillSession;

/**
 * 함께 채우기 이미지 바이트의 보관소 (#152). 지금은 DB({@link RdsFillImageStore}), 뒤에 S3 구현으로 갈아 끼운다.
 * 서비스는 id 문자열만 들고 바이트의 실제 위치를 모른다 — 저장소를 바꿀 지점을 이 인터페이스 하나로 좁히는 것이 목적이다.
 * 이미지 주소 형식({@code /fills/{code}/images/{id}})도 여기서 만들므로, 구현을 바꿔도 클라이언트 계약은 유지된다.
 */
public interface FillImageStore {

    /**
     * 바이트를 저장하고 이미지 id를 돌려준다. 호출자가 이미 검증한 Content-Type을 그대로 기록한다.
     *
     * @param session     이미지가 속한 세션 (만료 정리의 단위)
     * @param contentType 저장할 MIME 타입
     * @param body        이미지 바이트
     * @return 주소에 그대로 들어가는 이미지 id
     */
    String store(FillSession session, String contentType, byte[] body);

    /**
     * 이미지 바이트를 읽는다. 세션과 id가 함께 맞아야 한다 — id만으로는 열 수 없다.
     *
     * @throws com.caestro.server.global.exception.CustomException FILL_IMAGE_NOT_FOUND - 세션에 그 id가 없음
     */
    StoredImage load(FillSession session, String imageId);

    /** 이미지 하나를 지운다. 이미 없으면 조용히 넘어간다(교체·GIF 폐기 경로에서 재호출될 수 있다). */
    void delete(FillSession session, String imageId);

    /** 세션의 이미지를 전부 지운다 (세션 삭제·만료 정리). */
    void deleteBySession(Long sessionId);

    /** 세션에 남은 이미지 수. 고아 이미지 검증과 용량 점검에 쓴다. */
    long countBySession(Long sessionId);

    /** 클라이언트가 그대로 받아 그리는 이미지 주소. */
    String urlOf(String code, String imageId);
}
