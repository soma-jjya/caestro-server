package com.caestro.server.domain.support.controller;

import com.caestro.server.domain.fill.store.StoredImage;
import com.caestro.server.domain.support.controller.api.SupportAdminApi;
import com.caestro.server.domain.support.dto.request.CreateSupportMessageRequest;
import com.caestro.server.domain.support.dto.request.UpdateSupportTicketRequest;
import com.caestro.server.domain.support.dto.response.SupportAdminTicketResponse;
import com.caestro.server.domain.support.dto.response.SupportTicketCountResponse;
import com.caestro.server.domain.support.dto.response.SupportTicketPageResponse;
import com.caestro.server.domain.support.enums.SupportCategory;
import com.caestro.server.domain.support.enums.SupportStatus;
import com.caestro.server.domain.support.service.SupportAdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Optional;

@RestController
@RequestMapping("/admin/support/tickets")
@RequiredArgsConstructor
public class SupportAdminController implements SupportAdminApi {

    private final SupportAdminService adminService;

    @GetMapping
    @Override
    public ResponseEntity<SupportTicketPageResponse> list(@RequestParam(required = false) SupportStatus status,
                                                          @RequestParam(required = false) SupportCategory category,
                                                          @RequestParam(required = false) Long cursor,
                                                          @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok(adminService.list(status, category, cursor, size));
    }

    @GetMapping("/count")
    @Override
    public ResponseEntity<SupportTicketCountResponse> count(@RequestParam(required = false) SupportStatus status) {
        return ResponseEntity.ok(adminService.count(status));
    }

    @GetMapping("/{id}")
    @Override
    public ResponseEntity<SupportAdminTicketResponse> get(@PathVariable Long id) {
        return ResponseEntity.ok(adminService.get(id));
    }

    @PostMapping("/{id}/messages")
    @Override
    public ResponseEntity<SupportAdminTicketResponse> reply(@PathVariable Long id, @Valid @RequestBody CreateSupportMessageRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(adminService.reply(id, request));
    }

    @PatchMapping("/{id}")
    @Override
    public ResponseEntity<SupportAdminTicketResponse> update(@PathVariable Long id, @Valid @RequestBody UpdateSupportTicketRequest request) {
        return ResponseEntity.ok(adminService.update(id, request));
    }

    @GetMapping("/{id}/attachments/{attachmentId}")
    @Override
    public ResponseEntity<byte[]> attachment(@PathVariable Long id, @PathVariable Long attachmentId) {
        Optional<URI> redirect = adminService.attachmentRedirect(id, attachmentId);
        if (redirect.isPresent()) {
            return ResponseEntity.status(HttpStatus.FOUND).location(redirect.get()).cacheControl(CacheControl.noStore()).build();
        }
        StoredImage image = adminService.attachment(id, attachmentId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.contentType()))
                .cacheControl(CacheControl.noStore()).body(image.data());
    }
}
