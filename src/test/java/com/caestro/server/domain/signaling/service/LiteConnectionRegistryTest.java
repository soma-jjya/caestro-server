package com.caestro.server.domain.signaling.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LiteConnectionRegistryTest {

    @Mock
    private WebSocketSessionManager sessionManager;

    @InjectMocks
    private LiteConnectionRegistry registry;

    private static final String TOKEN = "tok123";

    @Test
    @DisplayName("등록된 연결이 없으면 살아있는 연결이 없다")
    void hasLiveConnection_noEntry_false() {
        assertThat(registry.hasLiveConnection(TOKEN)).isFalse();
    }

    @Test
    @DisplayName("등록된 소켓이 살아있으면 동시 접속으로 간주한다")
    void hasLiveConnection_openSocket_true() {
        registry.register(TOKEN, "socket-1");
        given(sessionManager.isConnected("socket-1")).willReturn(true);

        assertThat(registry.hasLiveConnection(TOKEN)).isTrue();
    }

    @Test
    @DisplayName("등록된 소켓이 이미 죽었으면 재연결로 간주하고 스테일 항목을 정리한다")
    void hasLiveConnection_deadSocket_falseAndCleaned() {
        registry.register(TOKEN, "socket-1");
        given(sessionManager.isConnected("socket-1")).willReturn(false);

        assertThat(registry.hasLiveConnection(TOKEN)).isFalse();
        // 정리되었으므로 이후 조회는 isConnected 호출 없이 바로 false
        assertThat(registry.hasLiveConnection(TOKEN)).isFalse();
    }

    @Test
    @DisplayName("연결 해제 후에는 살아있는 연결이 없다 (신규 접속처럼 처리)")
    void release_removesEntry() {
        registry.register(TOKEN, "socket-1");
        registry.release(TOKEN, "socket-1");

        assertThat(registry.hasLiveConnection(TOKEN)).isFalse();
    }

    @Test
    @DisplayName("다른 소켓 ID로는 해제되지 않는다 (kick 이후 새 연결 보호)")
    void release_differentSocket_doesNotRemove() {
        registry.register(TOKEN, "socket-new");
        registry.release(TOKEN, "socket-old");
        given(sessionManager.isConnected("socket-new")).willReturn(true);

        assertThat(registry.hasLiveConnection(TOKEN)).isTrue();
    }
}
