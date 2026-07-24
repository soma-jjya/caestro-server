package com.caestro.server.domain.signaling.service;

/**
 * 크로스 인스턴스 중계를 위해 Redis 채널로 발행되는 메시지 봉투.
 * "대상 소켓(socketId)에게 이 payload(이미 직렬화된 JSON)를 전달하라"는 요청을 담는다.
 *
 * @param socketId 최종 전달 대상 소켓 ID
 * @param payload  클라이언트에게 보낼 메시지의 JSON 문자열 (발행 측에서 직렬화 완료)
 */
public record SignalingRelayMessage(String socketId, String payload) {
}
