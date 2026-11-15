package com.caestro.server.domain.push.sender;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 보낼 알림 한 건. {@code type} 은 앱이 어디로 이동할지 정하는 키이고 {@code data} 에도 함께 실린다(앱은 type·code 만 본다).
 */
public record PushMessage(String type, String title, String body, Map<String, String> data) {

    public static final String TYPE_FILL_COMPLETED = "fill_completed";
    public static final String TYPE_FILL_EXPIRING = "fill_expiring";
    public static final String TYPE_FILL_PROGRESS = "fill_progress";
    public static final String TYPE_TEST = "test";
    public static final String TYPE_SUPPORT_REPLY = "support_reply";
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
     * 같은 순간 칸을 채운 참여자에게. 종류가 같아 앱은 주인의 것과 똑같이 세션 {@code code} 로 가고, 참여자도 그 화면에서 완성해 저장할 수 있다.
     */
    public static PushMessage fillCompletedForParticipant(String code) {
        return new PushMessage(TYPE_FILL_COMPLETED, "네컷이 완성됐어요",
                "함께 채운 네컷이 완성됐어요. 지금 저장해 보세요.", Map.of("code", code));
    }

    /** 이름이 길면 알림 한 줄이 넘친다. 이만큼에서 자르고 말줄임표. */
    static final int PROGRESS_NAME_MAX = 8;
    /** 이름 없는 참여자를 부르는 말. 웹 참여 화면의 기본 이름이기도 하다. */
    static final String UNNAMED_FRIEND = "친구";

    /**
     * 친구가 칸을 채웠다는 묶음 알림(#274)의 기본 문구. 넣을 때 행에 적어 두고, 실제로는 보내는 순간 {@link #fillProgress} 로 다시 만든다.
     */
    public static PushMessage fillProgressPlaceholder(String code) {
        return new PushMessage(TYPE_FILL_PROGRESS, "친구가 네컷을 채웠어요",
                "함께 채우는 네컷을 확인해 보세요.", Map.of("code", code));
    }

    /**
     * 친구가 칸을 채웠다는 묶음 알림(#274). 주인에게만. 탭하면 세션 {@code code} 로 간다(fill_completed 와 같은 규칙).
     * 제목은 누가, 본문은 얼마나 — "민지님 외 1명이 네컷을 채웠어요" / "'제주 여행' 4칸 중 3칸이 찼어요. 1칸만 더 채우면 완성이에요!"
     * 이름이 없거나 "친구"(웹 참여 기본값)면 "님"을 붙이지 않는다 — "친구가 …" / "친구 2명이 …" (#280: "친구님이"로 나갔다)
     *
     * @param label       세션 제목, 없으면 null(본문에서 뺀다)
     * @param latestName  가장 최근에 채운 친구의 이름
     * @param friends     칸에 사진이 있는 친구 수(주인 제외)
     * @param filled      사진이 있는 칸 수(주인 것 포함)
     * @param total       전체 칸 수
     */
    public static PushMessage fillProgress(String code, String label, String latestName, int friends, int filled, int total) {
        String name = latestName == null ? "" : latestName.strip();
        String title;
        if (name.isEmpty() || name.equals(UNNAMED_FRIEND)) {
            title = friends > 1 ? UNNAMED_FRIEND + " " + friends + "명이 네컷을 채웠어요" : UNNAMED_FRIEND + "가 네컷을 채웠어요";
        } else {
            String who = shorten(name, PROGRESS_NAME_MAX) + "님";
            title = friends > 1 ? who + " 외 " + (friends - 1) + "명이 네컷을 채웠어요" : who + "이 네컷을 채웠어요";
        }
        String where = label == null || label.isBlank() ? "" : "'" + shorten(label.strip(), 20) + "' ";
        String body = where + total + "칸 중 " + filled + "칸이 찼어요. " + (total - filled) + "칸만 더 채우면 완성이에요!";
        return new PushMessage(TYPE_FILL_PROGRESS, title, body, Map.of("code", code));
    }

    private static String shorten(String text, int max) {
        return text.codePointCount(0, text.length()) <= max ? text : text.substring(0, text.offsetByCodePoints(0, max)) + "…";
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

    /** 앱 문의에 운영자가 답했을 때(#263). 탭하면 앱이 문의 {@code ticketId} 상세로 간다. */
    public static PushMessage supportReply(long ticketId, String title) {
        return new PushMessage(TYPE_SUPPORT_REPLY, "문의에 답변이 도착했어요",
                "'" + title + "' 문의에 운영자가 답했어요. 지금 확인해 보세요.", Map.of("ticketId", Long.toString(ticketId)));
    }

    /** 앱 연동 확인용. 본인에게만 간다. */
    public static PushMessage test() {
        return new PushMessage(TYPE_TEST, "PeakPic 알림 테스트", "이 기기에 알림이 잘 도착했어요.", Map.of());
    }
}
