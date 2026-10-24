package com.caestro.server.domain.b2b.service;

import com.caestro.server.domain.b2b.entity.B2bImage;
import com.caestro.server.domain.b2b.repository.B2bImageRepository;
import com.caestro.server.domain.catalog.entity.CatalogImage;
import com.caestro.server.domain.catalog.repository.CatalogImageRepository;
import com.caestro.server.domain.fill.store.StoredImage;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class B2bImageService {
    private static final Set<String> TYPES = Set.of("image/png", "image/jpeg");
    private final CatalogImageRepository catalogImages;
    private final B2bImageRepository images;
    private final Clock clock;

    /**
     * 카탈로그 업로드를 B2B 소유로 복사한다. 현재 이미지와 같은 입력은 기존 사본을 재사용한다.
     * @param sourceId /catalog/studio/images 업로드의 imageId; null은 초안에서 이미지 제거
     * @param current 해당 프레임이 이미 보관 중인 사본
     * @return 불변 이미지 사본 또는 null
     * @throws CustomException B2B_INVALID_IMAGE - 업로드가 없거나 PNG/JPEG가 아님
     */
    @Transactional
    public B2bImage retain(String sourceId, B2bImage current) {
        if (sourceId == null) return null;
        if (current != null && sourceId.equals(current.getSourceImageId())) return current;
        CatalogImage source = catalogImages.findById(sourceId)
                .orElseThrow(() -> new CustomException(ErrorCode.B2B_INVALID_IMAGE));
        if (!TYPES.contains(source.getContentType()) || source.getWidth() < 1 || source.getHeight() < 1) {
            throw new CustomException(ErrorCode.B2B_INVALID_IMAGE);
        }
        return images.save(B2bImage.builder().id(B2bPolicy.id("bi_")).sourceImageId(sourceId)
                .contentType(source.getContentType()).data(source.getData().clone())
                .width(source.getWidth()).height(source.getHeight()).createdAt(LocalDateTime.now(clock)).build());
    }

    /** 난수 id로 불변 이미지 바이트를 제공한다. 없는 사본은 404. */
    @Transactional(readOnly = true)
    public StoredImage image(String id) {
        B2bImage image = images.findById(id).orElseThrow(() -> new CustomException(ErrorCode.B2B_IMAGE_NOT_FOUND));
        return new StoredImage(image.getId(), image.getContentType(), image.getData());
    }

    /** 선택 목록/기존 세션 양쪽에서 해제된 사본만 삭제한다. catalog_images에는 쓰지 않는다. */
    @Transactional
    public int purgeUnreferenced(LocalDateTime before) { return images.deleteUnreferencedBefore(before); }
}
