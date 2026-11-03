package com.caestro.server.domain.inquiry.controller;

import com.caestro.server.domain.inquiry.controller.api.InquiryAdminApi;
import com.caestro.server.domain.inquiry.dto.request.UpdateInquiryRequest;
import com.caestro.server.domain.inquiry.dto.response.InquiryCountResponse;
import com.caestro.server.domain.inquiry.dto.response.InquiryPageResponse;
import com.caestro.server.domain.inquiry.dto.response.InquiryResponse;
import com.caestro.server.domain.inquiry.enums.InquiryStatus;
import com.caestro.server.domain.inquiry.enums.InquiryType;
import com.caestro.server.domain.inquiry.service.InquiryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 페이지용. /b2b/admin/** 은 SecurityConfig 가 ROLE_STUDIO·ADMIN 으로 막는다. */
@RestController
@RequestMapping("/b2b/admin/inquiries")
@RequiredArgsConstructor
public class InquiryAdminController implements InquiryAdminApi {

    private final InquiryService inquiryService;

    @GetMapping
    @Override
    public ResponseEntity<InquiryPageResponse> list(@RequestParam(required = false) InquiryStatus status,
                                                    @RequestParam(required = false) InquiryType type,
                                                    @RequestParam(required = false) Long cursor,
                                                    @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok(inquiryService.list(status, type, cursor, size));
    }

    @GetMapping("/count")
    @Override
    public ResponseEntity<InquiryCountResponse> count(@RequestParam(required = false) InquiryStatus status) {
        return ResponseEntity.ok(inquiryService.count(status));
    }

    @GetMapping("/{id}")
    @Override
    public ResponseEntity<InquiryResponse> get(@PathVariable Long id) {
        return ResponseEntity.ok(inquiryService.get(id));
    }

    @PatchMapping("/{id}")
    @Override
    public ResponseEntity<InquiryResponse> update(@PathVariable Long id, @Valid @RequestBody UpdateInquiryRequest request) {
        return ResponseEntity.ok(inquiryService.update(id, request));
    }
}
