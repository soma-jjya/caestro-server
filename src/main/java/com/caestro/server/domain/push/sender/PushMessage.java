package com.caestro.server.domain.push.sender;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 보낼 알림 한 건. {@code type} 은 앱이 어디로 이동할지 정하는 키이고 {@code data} 에도 함께 실린다(앱은 type·code 만 본다).
 */
public record PushMessage(String type, String title, String body, Map<String, String> data) {

    public static final String TYPE_FILL_COMPLETED = "fill_completed";
    public static final String TYPE_FILL_EXPIRING = "fill_expiring";
    public static final String TYPE_TEST = "test";
    /** 알림함 행의 id (#211). 앱이 탭할 때 이 id 로 읽음을 표시한다. */
    public static final String DATA_NOTIFICATION_ID = "notificationId";

    /** 같은 알림에 알림함 행 id 를 실은 사본. */
    public PushMessage withNotificationId(long notificationId) {
        Map<String, String> merged = new LinkedHashMap<>(data);
        merged.put(DATA_NOTIFICATION_ID, Long.toString(notificationId));
        return new PushMessage(type, title, body, Map.copyOf(merged));
    }

    /** 함께 채우기 네 칸이 다 찼을 때 주인에게. 탭하면 앱이 세션 {@code code} 로 간다. */
    public static PushMessage fillCompleted(String code) {
        return new PushMessage(TYPE_FILL_COMPLETED, "네컷이 완성됐어요",
                "친구들이 네 칸을 모두 채웠어요. 지금 확인해 보세요.", Map.of("code", code));
    }

    /**
     * 함께 채우기 사진이 내일 지워진다는 안내(#213). 탭하면 세션 {@code code} 로 간다. 시각 대신 "내일"만 — 한눈에 행동이 보이게.
     *
     * @param label 세션 제목, 없으면 코드
     */
    public static PushMessage fillExpiring(String code, String label) {
        return new PushMessage(TYPE_FILL_EXPIRING, "네컷 사진이 곧 만료돼요!",
                "'" + label + "' 사진이 내일 사라져요. 지금 저장해 두세요.", Map.of("code", code));
    }

    /** 앱 연동 확인용. 본인에게만 간다. */
    public static PushMessage test() {
        return new PushMessage(TYPE_TEST, "PeakPic 알림 테스트", "이 기기에 알림이 잘 도착했어요.", Map.of());
    }
}
