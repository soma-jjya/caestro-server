package com.caestro.server.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClientDeviceTest {

    @Test
    @DisplayName("기기 키는 SHA-256 hex 로만 남긴다: 같은 값은 같은 해시, 앞뒤 공백 무시, 비었거나 너무 길면 null")
    void hash() {
        String a = ClientDevice.hash("9774d56d682e549c");
        assertThat(a).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(ClientDevice.hash(" 9774d56d682e549c ")).isEqualTo(a);
        assertThat(ClientDevice.hash("9774d56d682e549d")).isNotEqualTo(a);
        assertThat(ClientDevice.hash(null)).isNull();
        assertThat(ClientDevice.hash("  ")).isNull();
        assertThat(ClientDevice.hash("x".repeat(201))).isNull();
    }
}
