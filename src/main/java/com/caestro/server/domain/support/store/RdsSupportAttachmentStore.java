package com.caestro.server.domain.support.store;

import com.caestro.server.domain.fill.service.FillCodeGenerator;
import com.caestro.server.domain.fill.store.StoredImage;
import com.caestro.server.domain.support.entity.SupportAttachmentBlob;
import com.caestro.server.domain.support.repository.SupportAttachmentBlobRepository;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 문의 사진을 RDS({@code support_attachment_blobs} LONGBLOB)에 두는 구현 (#263). 로컬·CI 기본. */
@Component
@ConditionalOnProperty(name = "support.attachment-store", havingValue = "rds", matchIfMissing = true)
@RequiredArgsConstructor
public class RdsSupportAttachmentStore implements SupportAttachmentStore {

    private final SupportAttachmentBlobRepository blobRepository;
    private final FillCodeGenerator codeGenerator;

    @Override
    @Transactional
    public String store(Long ticketId, String contentType, byte[] body) {
        return blobRepository.save(SupportAttachmentBlob.builder()
                .id(codeGenerator.imageId())
                .ticketId(ticketId)
                .contentType(contentType)
                .data(body)
                .sizeBytes(body.length)
                .build()).getId();
    }

    @Override
    @Transactional(readOnly = true)
    public StoredImage load(Long ticketId, String storeId) {
        SupportAttachmentBlob blob = blobRepository.findByIdAndTicketId(storeId, ticketId)
                .orElseThrow(() -> new CustomException(ErrorCode.SUPPORT_ATTACHMENT_NOT_FOUND));
        return new StoredImage(blob.getId(), blob.getContentType(), blob.getData());
    }

    @Override
    @Transactional
    public void delete(Long ticketId, String storeId) {
        blobRepository.findByIdAndTicketId(storeId, ticketId).ifPresent(blobRepository::delete);
    }

    @Override
    @Transactional
    public void deleteByTicket(Long ticketId) {
        blobRepository.deleteByTicketId(ticketId);
    }
}
