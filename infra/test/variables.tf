variable "region" {
  description = "AWS 리전"
  type        = string
  default     = "ap-northeast-2" # 서울 (국내 사용자 저지연)
}

variable "backend_instance_type" {
  description = "백엔드 인스턴스 타입 (JVM+MySQL+Redis 때문에 최소 t4g.small 권장)"
  type        = string
  default     = "t4g.small"
}

variable "turn_instance_type" {
  description = "coturn 인스턴스 타입 (최소 비용)"
  type        = string
  default     = "t4g.nano"
}

variable "key_name" {
  description = "EC2 키페어 이름 (app.jar scp/SSH 위해 필수 - AWS 콘솔에서 미리 생성)"
  type        = string
}

variable "ssh_cidr" {
  description = "SSH(22) 허용 CIDR. 본인 IP/32 권장"
  type        = string
  default     = "0.0.0.0/0"
}

variable "subnet_cidr" {
  description = "생성할 서브넷 CIDR (null이면 자동 계산)"
  type        = string
  default     = null
}

variable "turn_user" {
  description = "TURN 사용자명"
  type        = string
  default     = "caestro"
}

variable "turn_password" {
  description = "TURN 비밀번호 (특수문자 없이 영문+숫자 권장)"
  type        = string
  sensitive   = true
}

variable "turn_realm" {
  description = "TURN realm"
  type        = string
  default     = "caestro"
}
