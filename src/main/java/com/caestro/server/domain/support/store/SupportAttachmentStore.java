package com.caestro.server.domain.support.store;

import com.caestro.server.domain.fill.store.StoredImage;

import java.net.URI;
import java.util.Optional;

/**
 * 문의 사진 바이트의 보관소 (#263). 네컷 이미지의 {@link com.caestro.server.domain.fill.store.FillImageStore}(#152)와 같은 분리 —
 * 서비스는 {@code storeId} 만 들고, RDS 와 S3 구현을 설정({@code support.attachment-store})으로 고른다.
 * 키는 문의 단위({@code {prefix}/{ticketId}/{storeId}})라 문의를 지울 때 접두사로 한 번에 지운다.
 */
public interface SupportAttachmentStore {

    /** 바이트를 저장하고 저장소 키를 돌려준다. Content-Type 은 호출자가 이미 판정한 것. */
    String store(Long ticketId, String contentType, byte[] body);

    /**
     * 바이트를 읽는다. 문의와 키가 함께 맞아야 한다.
     *
     * @throws com.caestro.server.global.exception.CustomException SUPPORT_ATTACHMENT_NOT_FOUND
     */
    StoredImage load(Long ticketId, String storeId);

    /** 바이트를 중계하는 대신 보낼 곳(짧은 수명의 서명 주소). 비어 있으면 {@link #load} 로 중계한다. */
    default Optional<URI> redirectUrl(Long ticketId, String storeId) {
        return Optional.empty();
    }

    /** 하나를 지운다. 이미 없으면 조용히 넘어간다. */
    void delete(Long ticketId, String storeId);

    /** 문의의 사진을 전부 지운다(보관 기간 정리). */
    void deleteByTicket(Long ticketId);
}
