package com.caestro.server.domain.fill.service;

import com.caestro.server.domain.fill.dto.FillCustomFrameDto;
import com.caestro.server.domain.fill.dto.FillLayerDto;
import com.caestro.server.domain.fill.dto.request.CreateFillRequest;
import com.caestro.server.domain.fill.dto.response.FillResponse;
import com.caestro.server.domain.fill.dto.response.FillSlotResponse;
import com.caestro.server.domain.fill.entity.FillImage;
import com.caestro.server.domain.fill.entity.FillSession;
import com.caestro.server.domain.fill.entity.FillSlot;
import com.caestro.server.domain.fill.repository.FillImageRepository;
import com.caestro.server.domain.fill.repository.FillSessionRepository;
import com.caestro.server.domain.fill.repository.FillSlotRepository;
import com.caestro.server.domain.user.entity.User;
import com.caestro.server.domain.user.repository.UserRepository;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 함께 채우기(#fill-together). 세션은 프레임과 네 칸의 상태만 들고, 합성은 클라이언트가 한다.
 * 규칙: 칸은 먼저 채운 사람 것(다른 사람이 채운 칸은 409), 비우기는 본인 칸 또는 세션 주인, 없애기는 주인만.
 * 이미지는 {@link FillImage}에 두고 인증 없는 난수 URL로 내려준다(웹 클라이언트와 앱이 같은 URL을 그대로 그린다).
 */
@Service
@RequiredArgsConstructor
public class FillService {

    public static final int SLOTS = 4;
    private static final int CODE_ATTEMPTS = 5;
    private static final Set<String> FRAMES = Set.of("Black", "White");
    private static final Set<String> LAYOUTS = Set.of("Strip", "Grid");

    private final FillSessionRepository sessionRepository;
    private final FillSlotRepository slotRepository;
    private final FillImageRepository imageRepository;
    private final UserRepository userRepository;
    private final FillCodeGenerator codeGenerator;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    // 이미지 URL의 호스트. ALB 뒤에서는 요청 스킴이 http로 보일 수 있어 요청에서 만들지 않고 고정한다.
    @Value("${fill.public-base-url:https://api.peakpic.app}")
    private String publicBaseUrl;

    @Value("${fill.ttl-days:7}")
    private long ttlDays;

    @Value("${fill.max-image-bytes:3145728}")
    private int maxImageBytes;

    @Transactional
    public FillResponse create(Long ownerId, CreateFillRequest request) {
        if (!FRAMES.contains(request.frame()) || !LAYOUTS.contains(request.layout())) {
            throw new CustomException(ErrorCode.FILL_INVALID_FRAME);
        }
        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        LocalDateTime now = LocalDateTime.now(clock);
        FillSession session = sessionRepository.save(FillSession.builder()
                .code(uniqueCode())
                .owner(owner)
                .frame(request.frame())
                .layout(request.layout())
                .artFrameId(request.artFrameId())
                .customFrameJson(request.customFrame() == null ? null : toJson(request.customFrame()))
                .title(normalizeTitle(request.title()))
                .expiresAt(now.plusDays(ttlDays))
                .build());
        List<FillSlot> slots = new ArrayList<>(SLOTS);
        for (int i = 0; i < SLOTS; i++) {
            slots.add(slotRepository.save(FillSlot.builder().session(session).slotIndex(i).build()));
        }
        return toResponse(session, slots, ownerId);
    }

    @Transactional(readOnly = true)
    public FillResponse get(String code, Long userId) {
        FillSession session = liveSession(code);
        return toResponse(session, slotRepository.findBySessionIdOrderBySlotIndex(session.getId()), userId);
    }

    /**
     * 칸 [index]에 사진을 넣는다. 비어 있거나 본인이 채운 칸만 가능하고, 다른 사람의 칸이면 409로 돌려보내
     * 클라이언트가 최신 상태를 다시 그리게 한다. 이전 사진은 지운다(교체).
     */
    @Transactional
    public FillResponse fillSlot(String code, int index, Long userId, String name, byte[] body, String contentType) {
        FillSession session = liveSession(code);
        FillSlot slot = slotOf(session, index);
        if (!slot.isEmpty() && !slot.isFilledBy(userId)) {
            throw new CustomException(ErrorCode.FILL_SLOT_TAKEN);
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        FillImage image = storeImage(session, body, contentType, Set.of("image/jpeg", "image/png"));
        FillImage previous = slot.getImage();
        String shownName = (name != null && !name.isBlank()) ? name.trim() : user.getNickname();
        slot.fill(user, shownName, image, LocalDateTime.now(clock));
        if (previous != null) {
            imageRepository.delete(previous);
        }
        return toResponse(session, slotRepository.findBySessionIdOrderBySlotIndex(session.getId()), userId);
    }

    /** 칸을 비운다: 본인이 채운 칸, 또는 세션 주인은 어느 칸이든. */
    @Transactional
    public FillResponse clearSlot(String code, int index, Long userId) {
        FillSession session = liveSession(code);
        FillSlot slot = slotOf(session, index);
        if (!slot.isEmpty() && !slot.isFilledBy(userId) && !session.isOwnedBy(userId)) {
            throw new CustomException(ErrorCode.FILL_ACCESS_DENIED);
        }
        FillImage previous = slot.getImage();
        slot.clear();
        if (previous != null) {
            imageRepository.delete(previous);
        }
        return toResponse(session, slotRepository.findBySessionIdOrderBySlotIndex(session.getId()), userId);
    }

    /** 커스텀 프레임의 k번째 요소 PNG. 주인만 올릴 수 있고, 저장하면 customFrame의 해당 layer.url이 채워진다. */
    @Transactional
    public String uploadFrameAsset(String code, int k, Long userId, byte[] body, String contentType) {
        FillSession session = liveSession(code);
        if (!session.isOwnedBy(userId)) {
            throw new CustomException(ErrorCode.FILL_ACCESS_DENIED);
        }
        FillCustomFrameDto custom = parseCustom(session.getCustomFrameJson());
        if (custom == null || custom.layers() == null || k < 0 || k >= custom.layers().size()) {
            throw new CustomException(ErrorCode.FILL_INVALID_SLOT);
        }
        FillImage image = storeImage(session, body, contentType, Set.of("image/png"));
        String url = imageUrl(session.getCode(), image.getId());
        List<FillLayerDto> layers = new ArrayList<>(custom.layers());
        layers.set(k, layers.get(k).withUrl(url));
        session.updateCustomFrameJson(toJson(new FillCustomFrameDto(custom.base(), layers, custom.borderless())));
        return url;
    }

    /** 세션을 없앤다(주인만): 칸, 사진, 요소가 모두 사라지고 링크는 더 열리지 않는다. */
    @Transactional
    public void delete(String code, Long userId) {
        FillSession session = sessionRepository.findByCode(code)
                .orElseThrow(() -> new CustomException(ErrorCode.FILL_NOT_FOUND));
        if (!session.isOwnedBy(userId)) {
            throw new CustomException(ErrorCode.FILL_ACCESS_DENIED);
        }
        purge(session);
    }

    /** 이미지 바이트(칸 사진·프레임 요소). 세션 코드와 이미지 id가 함께 맞아야 한다. */
    @Transactional(readOnly = true)
    public FillImage image(String code, String imageId) {
        FillSession session = sessionRepository.findByCode(code)
                .orElseThrow(() -> new CustomException(ErrorCode.FILL_NOT_FOUND));
        return imageRepository.findByIdAndSessionId(imageId, session.getId())
                .orElseThrow(() -> new CustomException(ErrorCode.FILL_IMAGE_NOT_FOUND));
    }

    /** 만료 세션 정리(배치): 만료 시각이 지난 세션을 사진까지 통째로 지우고 개수를 돌려준다. */
    @Transactional
    public int purgeExpired(LocalDateTime now) {
        List<FillSession> expired = sessionRepository.findByExpiresAtBefore(now);
        expired.forEach(this::purge);
        return expired.size();
    }

    private void purge(FillSession session) {
        slotRepository.deleteBySessionId(session.getId());
        imageRepository.deleteBySessionId(session.getId());
        sessionRepository.delete(session);
    }

    private FillSession liveSession(String code) {
        FillSession session = sessionRepository.findByCode(code.toUpperCase())
                .orElseThrow(() -> new CustomException(ErrorCode.FILL_NOT_FOUND));
        if (session.isExpired(LocalDateTime.now(clock))) {
            throw new CustomException(ErrorCode.FILL_EXPIRED);
        }
        return session;
    }

    private FillSlot slotOf(FillSession session, int index) {
        if (index < 0 || index >= SLOTS) {
            throw new CustomException(ErrorCode.FILL_INVALID_SLOT);
        }
        return slotRepository.findBySessionIdAndSlotIndex(session.getId(), index)
                .orElseThrow(() -> new CustomException(ErrorCode.FILL_INVALID_SLOT));
    }

    private FillImage storeImage(FillSession session, byte[] body, String contentType, Set<String> allowed) {
        if (body == null || body.length == 0 || !looksLikeImage(body)) {
            throw new CustomException(ErrorCode.FILL_INVALID_IMAGE);
        }
        if (body.length > maxImageBytes) {
            throw new CustomException(ErrorCode.FILL_IMAGE_TOO_LARGE);
        }
        String type = contentType == null ? "" : contentType.split(";")[0].trim().toLowerCase();
        if (!allowed.contains(type)) {
            // 헤더가 비었거나 다르면 바이트로 판단한다 — 브라우저 fetch가 Content-Type을 생략하는 경우 대비
            type = isPng(body) ? "image/png" : "image/jpeg";
            if (!allowed.contains(type)) {
                throw new CustomException(ErrorCode.FILL_INVALID_IMAGE);
            }
        }
        return imageRepository.save(FillImage.builder()
                .id(codeGenerator.imageId())
                .session(session)
                .contentType(type)
                .data(body)
                .sizeBytes(body.length)
                .build());
    }

    private static boolean looksLikeImage(byte[] b) {
        return isJpeg(b) || isPng(b);
    }

    private static boolean isJpeg(byte[] b) {
        return b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8;
    }

    private static boolean isPng(byte[] b) {
        return b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
    }

    private String uniqueCode() {
        for (int i = 0; i < CODE_ATTEMPTS; i++) {
            String code = codeGenerator.next();
            if (!sessionRepository.existsByCode(code)) {
                return code;
            }
        }
        throw new CustomException(ErrorCode.FILL_CODE_GENERATION_FAILED);
    }

    private String imageUrl(String code, String imageId) {
        return publicBaseUrl.replaceAll("/+$", "") + "/fills/" + code + "/images/" + imageId;
    }

    private FillResponse toResponse(FillSession session, List<FillSlot> slots, Long userId) {
        List<FillSlotResponse> slotResponses = new ArrayList<>(SLOTS);
        for (int i = 0; i < SLOTS; i++) {
            final int index = i;
            FillSlot slot = slots.stream().filter(s -> s.getSlotIndex() == index).findFirst().orElse(null);
            if (slot == null || slot.isEmpty()) {
                slotResponses.add(new FillSlotResponse(index, null, false, null, null));
            } else {
                slotResponses.add(new FillSlotResponse(
                        index,
                        imageUrl(session.getCode(), slot.getImage().getId()),
                        slot.isFilledBy(userId),
                        slot.getFilledByName(),
                        toInstant(slot.getFilledAt())));
            }
        }
        return new FillResponse(
                session.getCode(),
                session.isOwnedBy(userId),
                session.getFrame(),
                session.getLayout(),
                session.getArtFrameId(),
                parseCustom(session.getCustomFrameJson()),
                session.getTitle(),
                slotResponses,
                toInstant(session.getExpiresAt()),
                toInstant(session.getCreatedAt()));
    }

    /** 앞뒤 공백을 지우고 연속 공백을 하나로, 30자까지. 비면 null. */
    static String normalizeTitle(String raw) {
        if (raw == null) return null;
        String t = raw.trim().replaceAll("\\s+", " ");
        if (t.length() > 30) t = t.substring(0, 30);
        return t.isEmpty() ? null : t;
    }

    private Instant toInstant(LocalDateTime t) {
        return t == null ? null : t.atZone(clock.getZone()).toInstant();
    }

    private String toJson(FillCustomFrameDto dto) {
        try {
            return objectMapper.writeValueAsString(dto);
        } catch (JsonProcessingException e) {
            throw new CustomException(ErrorCode.FILL_INVALID_FRAME);
        }
    }

    private FillCustomFrameDto parseCustom(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, FillCustomFrameDto.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
