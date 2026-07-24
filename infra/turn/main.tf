terraform {
  required_version = ">= 1.5"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = var.region
}

# 기본 VPC/서브넷을 사용해 VPC·NAT 게이트웨이 생성을 피한다 (NAT는 월 $30+ 라 비용 폭탄 요인)
data "aws_vpc" "default" {
  default = true
}

# 기본 VPC에 붙어있는 인터넷 게이트웨이 (기본 VPC엔 항상 존재)
data "aws_internet_gateway" "default" {
  filter {
    name   = "attachment.vpc-id"
    values = [data.aws_vpc.default.id]
  }
}

data "aws_availability_zones" "available" {
  state = "available"
}

# 기본 서브넷이 삭제된 계정에서도 동작하도록 서브넷을 직접 생성한다.
resource "aws_subnet" "turn" {
  vpc_id                  = data.aws_vpc.default.id
  cidr_block              = var.subnet_cidr != null ? var.subnet_cidr : cidrsubnet(data.aws_vpc.default.cidr_block, 8, 0)
  availability_zone       = data.aws_availability_zones.available.names[0]
  map_public_ip_on_launch = true

  tags = {
    Name = "caestro-turn-subnet"
  }
}

# 인터넷으로 나가는 경로 (공인 IP로 도달 가능하게)
resource "aws_route_table" "turn" {
  vpc_id = data.aws_vpc.default.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = data.aws_internet_gateway.default.id
  }

  tags = {
    Name = "caestro-turn-rt"
  }
}

resource "aws_route_table_association" "turn" {
  subnet_id      = aws_subnet.turn.id
  route_table_id = aws_route_table.turn.id
}

# 최신 Ubuntu 22.04 ARM64 (t4g 계열용). Canonical 소유.
data "aws_ami" "ubuntu_arm" {
  most_recent = true
  owners      = ["099720109477"]

  filter {
    name   = "name"
    values = ["ubuntu/images/hvm-ssd/ubuntu-jammy-22.04-arm64-server-*"]
  }
  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }
}

resource "aws_security_group" "turn" {
  name        = "caestro-turn-sg"
  description = "coturn TURN server (STUN/TURN + relay ports)"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description = "SSH"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [var.ssh_cidr]
  }

  ingress {
    description = "STUN/TURN"
    from_port   = 3478
    to_port     = 3478
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  ingress {
    description = "STUN/TURN"
    from_port   = 3478
    to_port     = 3478
    protocol    = "udp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "TURN over TLS"
    from_port   = 5349
    to_port     = 5349
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  ingress {
    description = "TURN over TLS"
    from_port   = 5349
    to_port     = 5349
    protocol    = "udp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "TURN relay port range"
    from_port   = 49152
    to_port     = 65535
    protocol    = "udp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "caestro-turn-sg"
  }
}

resource "aws_instance" "turn" {
  ami                         = data.aws_ami.ubuntu_arm.id
  instance_type               = var.instance_type
  subnet_id                   = aws_subnet.turn.id
  vpc_security_group_ids      = [aws_security_group.turn.id]
  associate_public_ip_address = true # 자동 공인 IP 사용 (Elastic IP 미사용 → 중지 중 과금 없음)
  key_name                    = var.key_name

  root_block_device {
    volume_type = "gp3"
    volume_size = 8 # 최소 크기
  }

  metadata_options {
    http_tokens = "required" # IMDSv2 강제
  }

  user_data = templatefile("${path.module}/coturn_setup.sh.tftpl", {
    turn_user     = var.turn_user
    turn_password = var.turn_password
    turn_realm    = var.turn_realm
  })

  tags = {
    Name = "caestro-turn"
  }
}
