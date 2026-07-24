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

# --- 공용 네트워크 (기본 VPC 재사용, NAT 없음 → 비용 0) ---
data "aws_vpc" "default" {
  default = true
}

data "aws_internet_gateway" "default" {
  filter {
    name   = "attachment.vpc-id"
    values = [data.aws_vpc.default.id]
  }
}

data "aws_availability_zones" "available" {
  state = "available"
}

data "aws_ami" "ubuntu_arm" {
  most_recent = true
  owners      = ["099720109477"] # Canonical

  filter {
    name   = "name"
    values = ["ubuntu/images/hvm-ssd/ubuntu-jammy-22.04-arm64-server-*"]
  }
  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }
}

resource "aws_subnet" "main" {
  vpc_id                  = data.aws_vpc.default.id
  cidr_block              = var.subnet_cidr != null ? var.subnet_cidr : cidrsubnet(data.aws_vpc.default.cidr_block, 8, 0)
  availability_zone       = data.aws_availability_zones.available.names[0]
  map_public_ip_on_launch = true
  tags                    = { Name = "caestro-test-subnet" }
}

resource "aws_route_table" "main" {
  vpc_id = data.aws_vpc.default.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = data.aws_internet_gateway.default.id
  }
  tags = { Name = "caestro-test-rt" }
}

resource "aws_route_table_association" "main" {
  subnet_id      = aws_subnet.main.id
  route_table_id = aws_route_table.main.id
}

# --- Security Groups ---
resource "aws_security_group" "backend" {
  name        = "caestro-backend-sg"
  description = "Spring backend (REST + WebSocket signaling)"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description = "SSH"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [var.ssh_cidr]
  }
  ingress {
    description = "App (REST + WebSocket)"
    from_port   = 8080
    to_port     = 8080
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
  tags = { Name = "caestro-backend-sg" }
}

resource "aws_security_group" "turn" {
  name        = "caestro-turn-sg"
  description = "coturn (STUN/TURN + relay)"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description = "SSH"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [var.ssh_cidr]
  }
  ingress {
    description = "STUN/TURN TCP"
    from_port   = 3478
    to_port     = 3478
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  ingress {
    description = "STUN/TURN UDP"
    from_port   = 3478
    to_port     = 3478
    protocol    = "udp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  ingress {
    description = "TURN TLS TCP"
    from_port   = 5349
    to_port     = 5349
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  ingress {
    description = "TURN TLS UDP"
    from_port   = 5349
    to_port     = 5349
    protocol    = "udp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  ingress {
    description = "TURN relay ports (UDP)"
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
  tags = { Name = "caestro-turn-sg" }
}

# --- Backend 인스턴스 (Spring + MySQL + Redis, Docker) ---
resource "aws_instance" "backend" {
  ami                    = data.aws_ami.ubuntu_arm.id
  instance_type          = var.backend_instance_type
  subnet_id              = aws_subnet.main.id
  vpc_security_group_ids = [aws_security_group.backend.id]
  key_name               = var.key_name

  root_block_device {
    volume_type = "gp3"
    volume_size = 16
  }
  metadata_options {
    http_tokens = "required"
  }

  user_data = file("${path.module}/backend_setup.sh")
  tags      = { Name = "caestro-backend" }
}

resource "aws_eip" "backend" {
  instance = aws_instance.backend.id
  domain   = "vpc"
  tags     = { Name = "caestro-backend-eip" }
}

# --- TURN 인스턴스 (coturn) ---
# EIP를 먼저 할당하고 그 IP를 user_data(external-ip)에 주입 → 자동 IP/EIP 불일치 방지
resource "aws_eip" "turn" {
  domain = "vpc"
  tags   = { Name = "caestro-turn-eip" }
}

resource "aws_instance" "turn" {
  ami                    = data.aws_ami.ubuntu_arm.id
  instance_type          = var.turn_instance_type
  subnet_id              = aws_subnet.main.id
  vpc_security_group_ids = [aws_security_group.turn.id]
  key_name               = var.key_name

  root_block_device {
    volume_type = "gp3"
    volume_size = 8
  }
  metadata_options {
    http_tokens = "required"
  }

  user_data = templatefile("${path.module}/coturn_setup.sh.tftpl", {
    turn_user     = var.turn_user
    turn_password = var.turn_password
    turn_realm    = var.turn_realm
    public_ip     = aws_eip.turn.public_ip
  })
  tags = { Name = "caestro-turn" }
}

resource "aws_eip_association" "turn" {
  instance_id   = aws_instance.turn.id
  allocation_id = aws_eip.turn.id
}
