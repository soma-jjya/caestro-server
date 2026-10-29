package com.caestro.server.domain.push.sender;

import java.util.Map;

/**
 * 보낼 알림 한 건. {@code type} 은 앱이 어디로 이동할지 정하는 키이고 {@code data} 에도 함께 실린다(앱은 type·code 만 본다).
 */
public record PushMessage(String type, String title, String body, Map<String, String> data) {

    public static final String TYPE_FILL_COMPLETED = "fill_completed";
    public static final String TYPE_TEST = "test";

    /** 함께 채우기 네 칸이 다 찼을 때 주인에게. 탭하면 앱이 세션 {@code code} 로 간다. */
    public static PushMessage fillCompleted(String code) {
        return new PushMessage(TYPE_FILL_COMPLETED, "네컷이 완성됐어요",
                "친구들이 네 칸을 모두 채웠어요. 지금 확인해 보세요.", Map.of("code", code));
    }

    /** 앱 연동 확인용. 본인에게만 간다. */
    public static PushMessage test() {
        return new PushMessage(TYPE_TEST, "PeakPic 알림 테스트", "이 기기에 알림이 잘 도착했어요.", Map.of());
    }
}
