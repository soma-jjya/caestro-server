output "backend_public_ip" {
  description = "백엔드 공인 IP (Elastic IP)"
  value       = aws_eip.backend.public_ip
}

output "turn_public_ip" {
  description = "TURN 공인 IP (Elastic IP)"
  value       = aws_eip.turn.public_ip
}

output "signaling_url" {
  description = "모바일 클라이언트가 접속할 시그널링 WebSocket 주소"
  value       = "ws://${aws_eip.backend.public_ip}:8080/signaling"
}

output "turn_url" {
  description = "클라이언트 iceServers에 넣을 TURN URL"
  value       = "turn:${aws_eip.turn.public_ip}:3478"
}

output "swagger_url" {
  description = "Swagger UI"
  value       = "http://${aws_eip.backend.public_ip}:8080/api-docs"
}

output "kakao_redirect_uri" {
  description = "카카오 콘솔에 등록할 redirect_uri"
  value       = "http://${aws_eip.backend.public_ip}:8080/auth/kakao/callback"
}

output "google_redirect_uri" {
  description = "구글 콘솔에 등록할 redirect_uri"
  value       = "http://${aws_eip.backend.public_ip}:8080/auth/google/callback"
}

output "backend_ssh" {
  description = "백엔드 SSH 접속"
  value       = "ssh -i <${var.key_name}>.pem ubuntu@${aws_eip.backend.public_ip}"
}
