package com.caestro.server.domain.push.sender;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 묶음 알림 문구(#274): 제목은 누가, 본문은 얼마나. */
class PushMessageTest {

    @Test
    @DisplayName("이름이 있으면 '민지님이' / '민지님 외 1명이', 긴 이름은 8자에서 말줄임, 제목 없는 세션은 본문 앞 이름을 뺀다")
    void fillProgress_named() {
        assertThat(PushMessage.fillProgress("K7X2MQ", "제주 여행", "민지", 1, 3, 4).title()).isEqualTo("민지님이 네컷을 채웠어요");
        assertThat(PushMessage.fillProgress("K7X2MQ", "제주 여행", "민지", 2, 3, 4).title()).isEqualTo("민지님 외 1명이 네컷을 채웠어요");
        assertThat(PushMessage.fillProgress("K7X2MQ", null, "아주아주긴이름입니다요", 1, 2, 4).title()).isEqualTo("아주아주긴이름입…님이 네컷을 채웠어요");
        assertThat(PushMessage.fillProgress("K7X2MQ", null, "민지", 1, 2, 4).body()).isEqualTo("4칸 중 2칸이 찼어요. 2칸만 더 채우면 완성이에요!");
    }

    @Test
    @DisplayName("[재현 #280] 이름이 없거나 '친구'면 '친구님이'가 아니라 '친구가' / '친구 2명이'")
    void fillProgress_unnamedFriend() {
        assertThat(PushMessage.fillProgress("K7X2MQ", "강대근", null, 1, 3, 4).title()).isEqualTo("친구가 네컷을 채웠어요");
        assertThat(PushMessage.fillProgress("K7X2MQ", "강대근", "  ", 1, 3, 4).title()).isEqualTo("친구가 네컷을 채웠어요");
        assertThat(PushMessage.fillProgress("K7X2MQ", "강대근", "친구", 1, 3, 4).title()).isEqualTo("친구가 네컷을 채웠어요");
        assertThat(PushMessage.fillProgress("K7X2MQ", "강대근", "친구", 2, 3, 4).title()).isEqualTo("친구 2명이 네컷을 채웠어요");
    }

    @Test
    @DisplayName("완성 문구(#294): 네컷은 그대로 '네 칸', 한 칸짜리 한 컷은 '네컷·네 칸' 없이 사진으로 — 종류·이동 코드는 같다")
    void fillCompleted_followsSlotCount() {
        assertThat(PushMessage.fillCompleted("K7X2MQ")).isEqualTo(PushMessage.fillCompleted("K7X2MQ", 4));
        assertThat(PushMessage.fillCompleted("K7X2MQ", 4).body()).isEqualTo("친구들이 네 칸을 모두 채웠어요. 지금 확인해 보세요.");
        assertThat(PushMessage.fillCompletedForParticipant("K7X2MQ")).isEqualTo(PushMessage.fillCompletedForParticipant("K7X2MQ", 4));

        for (PushMessage single : java.util.List.of(PushMessage.fillCompleted("K7X2MQ", 1),
                PushMessage.fillCompletedForParticipant("K7X2MQ", 1))) {
            assertThat(single.type()).isEqualTo(PushMessage.TYPE_FILL_COMPLETED);
            assertThat(single.data()).containsEntry("code", "K7X2MQ");
            assertThat(single.title()).isEqualTo("사진이 완성됐어요");
            assertThat(single.body()).doesNotContain("네컷", "네 칸");
        }
    }
}
