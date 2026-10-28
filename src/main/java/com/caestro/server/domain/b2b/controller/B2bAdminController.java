package com.caestro.server.domain.b2b.controller;

import com.caestro.server.domain.b2b.controller.api.B2bAdminApi;
import com.caestro.server.domain.b2b.dto.request.*;
import com.caestro.server.domain.b2b.dto.response.B2bProjectResponse;
import com.caestro.server.domain.b2b.dto.response.B2bSlugCheckResponse;
import com.caestro.server.domain.b2b.service.B2bProjectService;
import com.caestro.server.domain.b2b.service.B2bProjectDeletionService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/b2b/admin/projects")
@RequiredArgsConstructor
public class B2bAdminController implements B2bAdminApi {
    private final B2bProjectService service;
    private final B2bProjectDeletionService deletion;

    @Override @GetMapping
    public ResponseEntity<List<B2bProjectResponse>> list() { return ResponseEntity.ok(service.list()); }

    @Override @GetMapping("/{id}")
    public ResponseEntity<B2bProjectResponse> get(@PathVariable String id) { return ResponseEntity.ok(service.get(id)); }

    @Override @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        deletion.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Override @PostMapping
    public ResponseEntity<B2bProjectResponse> create(@Valid @RequestBody UpsertB2bProjectRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @Override @PutMapping("/{id}")
    public ResponseEntity<B2bProjectResponse> update(@PathVariable String id, @Valid @RequestBody UpsertB2bProjectRequest request) {
        return ResponseEntity.ok(service.update(id, request));
    }

    @Override @PutMapping("/{id}/frames")
    public ResponseEntity<B2bProjectResponse> replaceFrames(@PathVariable String id, @Valid @RequestBody ReplaceB2bFramesRequest request) {
        return ResponseEntity.ok(service.replaceFrames(id, request));
    }

    @Override @PostMapping("/{id}/publish")
    public ResponseEntity<B2bProjectResponse> publish(@PathVariable String id) { return ResponseEntity.ok(service.publish(id)); }

    @Override @PostMapping("/{id}/pause")
    public ResponseEntity<B2bProjectResponse> pause(@PathVariable String id) { return ResponseEntity.ok(service.pause(id)); }

    @Override @PostMapping("/{id}/resume")
    public ResponseEntity<B2bProjectResponse> resume(@PathVariable String id) { return ResponseEntity.ok(service.resume(id)); }

    @Override @PostMapping("/{id}/duplicate")
    public ResponseEntity<B2bProjectResponse> duplicate(@PathVariable String id, @Valid @RequestBody DuplicateB2bProjectRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.duplicate(id, request));
    }

    @Override @GetMapping("/slug-check")
    public ResponseEntity<B2bSlugCheckResponse> slugCheck(@RequestParam String slug, @RequestParam(required = false) String exclude) {
        return ResponseEntity.ok(service.slugCheck(slug, exclude));
    }
}
