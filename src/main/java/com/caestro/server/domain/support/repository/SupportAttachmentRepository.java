package com.caestro.server.domain.support.repository;

import com.caestro.server.domain.support.entity.SupportAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SupportAttachmentRepository extends JpaRepository<SupportAttachment, Long> {
    Optional<SupportAttachment> findByIdAndTicketId(Long id, Long ticketId);
    long countByTicketId(Long ticketId);
}
