package com.caestro.server.domain.b2b.service;

import com.caestro.server.domain.b2b.dto.request.CreateB2bFillRequest;
import com.caestro.server.domain.b2b.dto.response.B2bFillResponse;
import com.caestro.server.domain.b2b.dto.response.B2bFrameResponse;
import com.caestro.server.domain.b2b.entity.B2bFill;
import com.caestro.server.domain.b2b.entity.B2bFrame;
import com.caestro.server.domain.b2b.entity.B2bProject;
import com.caestro.server.domain.b2b.repository.B2bFillRepository;
import com.caestro.server.domain.b2b.repository.B2bFrameRepository;
import com.caestro.server.domain.b2b.repository.B2bProjectRepository;
import com.caestro.server.domain.fill.dto.request.CreateFillRequest;
import com.caestro.server.domain.fill.dto.response.FillResponse;
import com.caestro.server.domain.fill.entity.FillSession;
import com.caestro.server.domain.fill.repository.FillSessionRepository;
import com.caestro.server.domain.fill.service.FillService;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** B2B 선택·스냅샷을 담당하고 사진/코드/만료는 기존 FillService에 위임하는 연결 계층. */
@Service
@RequiredArgsConstructor
public class B2bFillService {
    private final B2bProjectRepository projects;
    private final B2bFrameRepository frames;
    private final B2bFillRepository b2bFills;
    private final FillSessionRepository sessions;
    private final FillService fills;
    private final B2bFrameMapper mapper;
    private final Clock clock;
    @Value("${b2b.web-base-url:https://peakpic.app/go}")
    private String webBaseUrl;

    /**
     * 공개 중인 프로젝트의 현재 프레임 버전으로 네컷을 만든다. 상태/프레임 수정과 동일한 행을 잠근다.
     * @param slug 공개 페이지의 slug
     * @param ownerId 기존 게스트 또는 회원 JWT의 사용자 id
     * @param request 선택한 프레임 id, 버전, 선택적 제목
     * @return 고정 프레임과 공유 주소 및 기존 fill 상태
     * @throws CustomException 404 - LIVE가 아니거나 다른 프로젝트의 프레임, 409 - 버전 변경
     */
    @Transactional
    public B2bFillResponse create(String slug, Long ownerId, CreateB2bFillRequest request) {
        // 1. 일시중지/프레임 저장과 참여 생성을 직렬화한다.
        B2bProject project = projects.findLockedBySlug(slug)
                .filter(p -> p.getStatus() == B2bProject.Status.LIVE)
                .orElseThrow(() -> new CustomException(ErrorCode.B2B_PROJECT_NOT_FOUND));
        B2bFrame frame = frames.findByProjectIdOrderByDisplayOrderAsc(project.getId()).stream()
                .filter(f -> f.getId().equals(request.frameId())).findFirst()
                .orElseThrow(() -> new CustomException(ErrorCode.B2B_FRAME_NOT_FOUND));
        if (request.frameVersion() == null || frame.getVersion() != request.frameVersion()) {
            throw new CustomException(ErrorCode.B2B_FRAME_VERSION_CHANGED);
        }
        B2bFrameResponse snapshot = mapper.response(frame);

        // 2. 저장소/코드/만료는 재사용한다. 공개 Fill 요청으로 고정 플래그를 지정할 수는 없다.
        String layout = frame.getLayout() == B2bFrame.Layout.STRIP ? "Strip" : "Grid";
        FillResponse fill = fills.createWithLockedFrame(ownerId,
                new CreateFillRequest("White", layout, null, null, request.title(), false));
        FillSession session = sessions.findByCode(fill.code())
                .orElseThrow(() -> new CustomException(ErrorCode.FILL_NOT_FOUND));

        // 3. 프레임 행 대신 값과 이미지 사본을 참조해 이후 수정/삭제와 분리한다.
        b2bFills.save(B2bFill.builder().id(B2bPolicy.id("bs_")).session(session).project(project)
                .image(frame.getImage()).frameJson(mapper.json(snapshot)).createdAt(LocalDateTime.now(clock)).build());
        return response(project.getSlug(), snapshot, fill);
    }

    /** slug와 code의 소속을 함께 검사한다. 중지된 프로젝트도 기존 fill의 만료(410)까지 조회 가능하다. */
    @Transactional(readOnly = true)
    public B2bFillResponse get(String slug, String code, Long userId) {
        String normalized = code == null ? "" : code.strip().toUpperCase(Locale.ROOT);
        B2bFill b2bFill = b2bFills.findByProjectSlugAndSessionCode(slug, normalized)
                .orElseThrow(() -> new CustomException(ErrorCode.B2B_FILL_NOT_FOUND));
        return response(slug, mapper.snapshot(b2bFill.getFrameJson()), fills.get(normalized, userId));
    }

    private B2bFillResponse response(String slug, B2bFrameResponse frame, FillResponse fill) {
        return new B2bFillResponse(slug, webBaseUrl.replaceAll("/+$", "") + "/" + slug + "/" + fill.code(), frame, fill);
    }
}
