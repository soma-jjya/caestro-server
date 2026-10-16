package com.caestro.server.domain.fill.store;

import com.caestro.server.domain.fill.entity.FillImage;
import com.caestro.server.domain.fill.entity.FillSession;
import com.caestro.server.domain.fill.repository.FillImageRepository;
import com.caestro.server.domain.fill.service.FillCodeGenerator;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 이미지 바이트를 RDS({@code fill_images} LONGBLOB)에 두는 구현 (#152).
 * 세션당 몇 MB에 7일 만료라 객체 저장소 없이 운영이 가장 단순했다. 이미지 GET이 앱 서버의 스레드와
 * 커넥션 풀을 쓰고 백업 스냅샷에 사진이 함께 들어가는 것이 대가이며, GIF로 세션당 용량이 서너 배가 되면
 * S3 구현으로 교체한다 — 교체 지점이 이 클래스다.
 */
@Component
@RequiredArgsConstructor
public class RdsFillImageStore implements FillImageStore {

    private final FillImageRepository imageRepository;
    private final FillCodeGenerator codeGenerator;

    // 이미지 주소의 호스트. ALB 뒤에서는 요청 스킴이 http로 보일 수 있어 요청에서 만들지 않고 고정한다.
    @Value("${fill.public-base-url:https://api.peakpic.app}")
    private String publicBaseUrl;

    @Override
    public String store(FillSession session, String contentType, byte[] body) {
        FillImage saved = imageRepository.save(FillImage.builder()
                .id(codeGenerator.imageId())
                .session(session)
                .contentType(contentType)
                .data(body)
                .sizeBytes(body.length)
                .build());
        return saved.getId();
    }

    @Override
    public StoredImage load(FillSession session, String imageId) {
        FillImage image = imageRepository.findByIdAndSessionId(imageId, session.getId())
                .orElseThrow(() -> new CustomException(ErrorCode.FILL_IMAGE_NOT_FOUND));
        return new StoredImage(image.getId(), image.getContentType(), image.getData());
    }

    @Override
    public void delete(FillSession session, String imageId) {
        if (imageId == null) {
            return;
        }
        imageRepository.findByIdAndSessionId(imageId, session.getId()).ifPresent(imageRepository::delete);
    }

    @Override
    public void deleteBySession(Long sessionId) {
        imageRepository.deleteBySessionId(sessionId);
    }

    @Override
    public long countBySession(Long sessionId) {
        return imageRepository.countBySessionId(sessionId);
    }

    @Override
    public String urlOf(String code, String imageId) {
        return publicBaseUrl.replaceAll("/+$", "") + "/fills/" + code + "/images/" + imageId;
    }
}
