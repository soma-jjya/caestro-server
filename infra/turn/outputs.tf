output "turn_public_ip" {
  description = "TURN 서버 공인 IP"
  value       = aws_instance.turn.public_ip
}

output "turn_url" {
  description = "클라이언트 iceServers에 넣을 TURN URL"
  value       = "turn:${aws_instance.turn.public_ip}:3478"
}

output "trickle_ice_hint" {
  description = "Trickle ICE 테스트 입력값"
  value       = "URL=turn:${aws_instance.turn.public_ip}:3478  username=${var.turn_user}  credential=<turn_password>"
}

output "ssh_hint" {
  description = "SSH 접속 명령 (key_name 지정 시)"
  value       = var.key_name == null ? "key_name 미지정 - SSH 불가(자동 구성됨)" : "ssh -i <${var.key_name}>.pem ubuntu@${aws_instance.turn.public_ip}"
}
