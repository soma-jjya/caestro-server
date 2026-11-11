package com.caestro.server.domain.support.repository;

import com.caestro.server.domain.support.entity.SupportAttachmentBlob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SupportAttachmentBlobRepository extends JpaRepository<SupportAttachmentBlob, String> {
    Optional<SupportAttachmentBlob> findByIdAndTicketId(String id, Long ticketId);
    void deleteByTicketId(Long ticketId);
    long countByTicketId(Long ticketId);
}
