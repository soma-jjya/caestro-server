package com.caestro.server.domain.support.controller;

import com.caestro.server.domain.fill.store.StoredImage;
import com.caestro.server.domain.support.controller.api.SupportApi;
import com.caestro.server.domain.support.dto.request.CreateSupportMessageRequest;
import com.caestro.server.domain.support.dto.request.CreateSupportTicketRequest;
import com.caestro.server.domain.support.dto.response.SupportAttachmentResponse;
import com.caestro.server.domain.support.dto.response.SupportTicketPageResponse;
import com.caestro.server.domain.support.dto.response.SupportTicketResponse;
import com.caestro.server.domain.support.service.SupportService;
import com.caestro.server.global.security.CustomUserDetails;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Optional;

@RestController
@RequestMapping("/support/tickets")
@RequiredArgsConstructor
public class SupportController implements SupportApi {

    private final SupportService supportService;

    @PostMapping
    @Override
    public ResponseEntity<SupportTicketResponse> create(@Valid @RequestBody CreateSupportTicketRequest request,
                                                        @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.status(HttpStatus.CREATED).body(supportService.create(userDetails.getUserId(), request));
    }

    @GetMapping
    @Override
    public ResponseEntity<SupportTicketPageResponse> list(@RequestParam(required = false) Long cursor,
                                                          @RequestParam(required = false) Integer size,
                                                          @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(supportService.list(userDetails.getUserId(), cursor, size));
    }

    @GetMapping("/{id}")
    @Override
    public ResponseEntity<SupportTicketResponse> get(@PathVariable Long id, @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(supportService.get(userDetails.getUserId(), id));
    }

    @PostMapping("/{id}/messages")
    @Override
    public ResponseEntity<SupportTicketResponse> addMessage(@PathVariable Long id, @Valid @RequestBody CreateSupportMessageRequest request,
                                                            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.status(HttpStatus.CREATED).body(supportService.addMessage(userDetails.getUserId(), id, request));
    }

    @PostMapping(value = "/{id}/attachments",
            consumes = {MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE, MediaType.APPLICATION_OCTET_STREAM_VALUE})
    @Override
    public ResponseEntity<SupportAttachmentResponse> addAttachment(@PathVariable Long id, @RequestBody byte[] body,
                                                                   @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType,
                                                                   @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.status(HttpStatus.CREATED).body(supportService.addAttachment(userDetails.getUserId(), id, body, contentType));
    }

    @DeleteMapping("/{id}/attachments/{attachmentId}")
    @Override
    public ResponseEntity<Void> deleteAttachment(@PathVariable Long id, @PathVariable Long attachmentId,
                                                 @AuthenticationPrincipal CustomUserDetails userDetails) {
        supportService.deleteAttachment(userDetails.getUserId(), id, attachmentId);
        return ResponseEntity.noContent().build();
    }

    // 본인 인증이 붙는 주소라 캐시하지 않는다(서명 주소도 수명이 있다)
    @GetMapping("/{id}/attachments/{attachmentId}")
    @Override
    public ResponseEntity<byte[]> attachment(@PathVariable Long id, @PathVariable Long attachmentId,
                                             @AuthenticationPrincipal CustomUserDetails userDetails) {
        Optional<URI> redirect = supportService.attachmentRedirect(userDetails.getUserId(), id, attachmentId);
        if (redirect.isPresent()) {
            return ResponseEntity.status(HttpStatus.FOUND).location(redirect.get()).cacheControl(CacheControl.noStore()).build();
        }
        StoredImage image = supportService.attachment(userDetails.getUserId(), id, attachmentId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.contentType()))
                .cacheControl(CacheControl.noStore()).body(image.data());
    }
}
