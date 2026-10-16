package com.caestro.server.domain.fill.store;

import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import java.util.Set;

/**
 * 이미지 바이트 판별 (#152, 기존 FillService에서 옮겨옴). 저장소 구현과 무관해 정적 유틸로 둔다.
 * 헤더를 믿지 않고 앞 몇 바이트(매직 넘버)로 판정한다 — 브라우저 fetch가 Content-Type을 생략하는 경우가 있다.
 */
public final class FillImageTypes {

    private FillImageTypes() {
    }

    /**
     * 저장할 Content-Type을 정한다. 헤더가 허용 목록에 있으면 그것, 아니면 바이트로 판정한다.
     *
     * @param body     이미지 바이트
     * @param declared 요청 헤더의 Content-Type (없거나 틀릴 수 있다)
     * @param allowed  이 경로에서 허용하는 MIME 타입
     * @throws CustomException FILL_INVALID_IMAGE - 이미지가 아니거나 허용 목록에 없는 형식
     */
    public static String resolve(byte[] body, String declared, Set<String> allowed) {
        if (body == null || body.length == 0 || !looksLikeImage(body)) {
            throw new CustomException(ErrorCode.FILL_INVALID_IMAGE);
        }
        String type = declared == null ? "" : declared.split(";")[0].trim().toLowerCase();
        if (allowed.contains(type)) {
            return type;
        }
        String sniffed = isPng(body) ? "image/png" : isGif(body) ? "image/gif" : "image/jpeg";
        if (!allowed.contains(sniffed)) {
            throw new CustomException(ErrorCode.FILL_INVALID_IMAGE);
        }
        return sniffed;
    }

    public static boolean looksLikeImage(byte[] b) {
        return isJpeg(b) || isPng(b) || isGif(b);
    }

    public static boolean isGif(byte[] b) {
        return b != null && b.length > 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8';
    }

    public static boolean isJpeg(byte[] b) {
        return b != null && b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8;
    }

    public static boolean isPng(byte[] b) {
        return b != null && b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
    }
}
