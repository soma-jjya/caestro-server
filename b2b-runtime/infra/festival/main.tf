provider "aws" {
  region              = var.region
  allowed_account_ids = [var.account_id]
  default_tags {
    tags = { Service = "PeakPicB2B", ProjectId = var.project_id, RuntimeId = var.runtime_id, Environment = var.environment }
  }
}
locals {
  name      = "pp-b2b-${var.runtime_id}"
  db_name   = "b2b_${trimprefix(var.project_id, "bp_")}_${var.runtime_id}"
  prefix    = "b2b/${var.environment}/${var.project_id}/${var.runtime_id}"
  bucket    = "arn:aws:s3:::${var.bucket_name}"
  ec2_trust = jsonencode({ Version = "2012-10-17", Statement = [{ Effect = "Allow", Principal = { Service = "ec2.amazonaws.com" }, Action = "sts:AssumeRole" }] })
}
resource "aws_vpc" "event" {
  cidr_block           = "10.80.0.0/16"
  enable_dns_support   = true
  enable_dns_hostnames = true
}
resource "aws_internet_gateway" "event" { vpc_id = aws_vpc.event.id }
resource "aws_subnet" "public" {
  count             = 2
  vpc_id            = aws_vpc.event.id
  cidr_block        = cidrsubnet(aws_vpc.event.cidr_block, 8, count.index)
  availability_zone = var.availability_zones[count.index]
}
resource "aws_subnet" "private" {
  count             = 2
  vpc_id            = aws_vpc.event.id
  cidr_block        = cidrsubnet(aws_vpc.event.cidr_block, 8, count.index + 10)
  availability_zone = var.availability_zones[count.index]
}
resource "aws_route_table" "public" {
  vpc_id = aws_vpc.event.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.event.id
  }
}
resource "aws_route_table_association" "public" {
  count          = 2
  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}
resource "aws_security_group" "alb" {
  name   = "${local.name}-alb"
  vpc_id = aws_vpc.event.id
}
resource "aws_security_group" "app" {
  name   = "${local.name}-app"
  vpc_id = aws_vpc.event.id
}
resource "aws_security_group" "data" {
  name   = "${local.name}-data"
  vpc_id = aws_vpc.event.id
}
resource "aws_vpc_security_group_ingress_rule" "https" {
  security_group_id = aws_security_group.alb.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}
resource "aws_vpc_security_group_egress_rule" "alb_app" {
  security_group_id            = aws_security_group.alb.id
  referenced_security_group_id = aws_security_group.app.id
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
}
resource "aws_vpc_security_group_ingress_rule" "app" {
  security_group_id            = aws_security_group.app.id
  referenced_security_group_id = aws_security_group.alb.id
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
}
resource "aws_vpc_security_group_egress_rule" "app_out" {
  security_group_id = aws_security_group.app.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}
resource "aws_vpc_security_group_ingress_rule" "data" {
  for_each                     = toset(["3306", "6379"])
  security_group_id            = aws_security_group.data.id
  referenced_security_group_id = aws_security_group.app.id
  ip_protocol                  = "tcp"
  from_port                    = tonumber(each.value)
  to_port                      = tonumber(each.value)
}
resource "aws_db_subnet_group" "event" {
  name       = local.name
  subnet_ids = aws_subnet.private[*].id
}
data "aws_secretsmanager_secret_version" "runtime" { secret_id = var.auth_secret_arn }
resource "aws_db_instance" "event" {
  identifier                = local.name
  engine                    = "mysql"
  engine_version            = var.db_engine_version
  instance_class            = var.db_instance_class
  db_name                   = local.db_name
  username                  = "b2b_operator"
  password                  = jsondecode(data.aws_secretsmanager_secret_version.runtime.secret_string).dbPassword
  allocated_storage         = var.db_allocated_storage_gb
  max_allocated_storage     = var.db_max_storage_gb
  storage_encrypted         = true
  storage_type              = var.db_storage_type
  multi_az                  = var.db_multi_az
  publicly_accessible       = false
  db_subnet_group_name      = aws_db_subnet_group.event.name
  vpc_security_group_ids    = [aws_security_group.data.id]
  backup_retention_period   = 7
  deletion_protection       = var.environment == "prod"
  skip_final_snapshot       = var.environment == "test"
  final_snapshot_identifier = "${local.name}-final"
}
resource "aws_elasticache_subnet_group" "event" {
  name       = local.name
  subnet_ids = aws_subnet.private[*].id
}
resource "aws_elasticache_replication_group" "event" {
  replication_group_id       = local.name
  description                = "Isolated B2B guest tokens and limits"
  engine                     = "redis"
  engine_version             = var.redis_engine_version
  node_type                  = var.redis_node_type
  num_cache_clusters         = var.redis_node_count
  automatic_failover_enabled = var.redis_node_count == 2
  multi_az_enabled           = var.redis_multi_az
  at_rest_encryption_enabled = true
  transit_encryption_enabled = true
  subnet_group_name          = aws_elasticache_subnet_group.event.name
  security_group_ids         = [aws_security_group.data.id]
}
resource "aws_cloudwatch_log_group" "event" {
  name              = "/peakpic-b2b/${var.runtime_id}"
  retention_in_days = 14
}
resource "aws_lb" "event" {
  name                       = local.name
  load_balancer_type         = "application"
  subnets                    = aws_subnet.public[*].id
  security_groups            = [aws_security_group.alb.id]
  enable_deletion_protection = var.environment == "prod"
  xff_header_processing_mode = "append"
  enable_xff_client_port     = false
}
resource "aws_lb_target_group" "event" {
  name        = local.name
  port        = 8080
  protocol    = "HTTP"
  target_type = "instance"
  vpc_id      = aws_vpc.event.id
  health_check {
    path    = "/projects/${var.slug}/actuator/health/readiness"
    matcher = "200"
  }
}
resource "aws_lb_listener" "event" {
  load_balancer_arn = aws_lb.event.arn
  port              = 443
  protocol          = "HTTPS"
  certificate_arn   = var.certificate_arn
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.event.arn
  }
}
