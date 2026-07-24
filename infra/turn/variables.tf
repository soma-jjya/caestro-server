variable "region" {
  description = "AWS 리전"
  type        = string
  default     = "ap-northeast-2" # 서울
}

variable "instance_type" {
  description = "인스턴스 타입 (최소 비용: t4g.nano ARM)"
  type        = string
  default     = "t4g.nano"
}

variable "key_name" {
  description = "SSH 접속용 기존 EC2 키페어 이름 (없으면 null - user_data로 자동 구성되므로 SSH 없이도 동작)"
  type        = string
  default     = null
}

variable "subnet_cidr" {
  description = "생성할 서브넷 CIDR (null이면 기본 VPC CIDR에서 자동 계산). 이미 서브넷이 있는 계정에서 충돌 시 지정"
  type        = string
  default     = null
}

variable "ssh_cidr" {
  description = "SSH(22) 허용 CIDR. 보안상 본인 IP/32 권장 (예: 1.2.3.4/32)"
  type        = string
  default     = "0.0.0.0/0"
}

variable "turn_user" {
  description = "TURN 사용자명"
  type        = string
  default     = "caestro"
}

variable "turn_password" {
  description = "TURN 비밀번호 (terraform.tfvars로 주입 권장)"
  type        = string
  sensitive   = true
}

variable "turn_realm" {
  description = "TURN realm"
  type        = string
  default     = "caestro"
}
