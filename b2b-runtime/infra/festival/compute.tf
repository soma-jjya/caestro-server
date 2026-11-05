data "aws_ami" "runtime" {
  owners = [var.account_id]
  filter {
    name   = "image-id"
    values = [var.ami_id]
  }
}
resource "aws_iam_role" "runtime" {
  name               = "${local.name}-ec2"
  assume_role_policy = local.ec2_trust
}
resource "aws_iam_instance_profile" "runtime" {
  name = local.name
  role = aws_iam_role.runtime.name
}
resource "aws_iam_role_policy_attachment" "ssm" {
  role       = aws_iam_role.runtime.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}
resource "aws_iam_role_policy" "runtime" {
  role = aws_iam_role.runtime.id
  policy = jsonencode({ Version = "2012-10-17", Statement = [
    { Effect = "Allow", Action = ["ecr:GetAuthorizationToken"], Resource = "*" },
    { Effect = "Allow", Action = ["ecr:BatchCheckLayerAvailability", "ecr:GetDownloadUrlForLayer", "ecr:BatchGetImage"], Resource = "arn:aws:ecr:${var.region}:${var.account_id}:repository/peakpic-b2b-server" },
    { Effect = "Allow", Action = ["logs:CreateLogStream", "logs:PutLogEvents"], Resource = "${aws_cloudwatch_log_group.event.arn}:*" },
    { Effect = "Allow", Action = ["secretsmanager:GetSecretValue"], Resource = var.auth_secret_arn },
    { Effect = "Allow", Action = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"], Resource = "${local.bucket}/${local.prefix}/*" },
    { Effect = "Allow", Action = "s3:ListBucket", Resource = local.bucket, Condition = { StringLike = { "s3:prefix" = ["${local.prefix}/*"] } } },
    { Effect = "Allow", Action = "cloudwatch:PutMetricData", Resource = "*", Condition = { StringEquals = { "cloudwatch:namespace" = "PeakPicB2B/Host" } } }
  ] })
}
locals {
  runtime_environment = {
    B2B_DB_URL                  = "jdbc:mysql://${aws_db_instance.event.endpoint}/${local.db_name}?sslMode=VERIFY_IDENTITY"
    B2B_DB_USERNAME             = "b2b_operator"
    B2B_DB_POOL_SIZE            = tostring(var.db_pool_size)
    B2B_REDIS_HOST              = aws_elasticache_replication_group.event.primary_endpoint_address
    B2B_REDIS_TLS               = "true"
    B2B_ENVIRONMENT             = var.environment
    B2B_PROJECT_ID              = var.project_id
    B2B_PROJECT_SLUG            = var.slug
    B2B_RUNTIME_ID              = var.runtime_id
    B2B_API_BASE_URL            = var.api_base_url
    B2B_STARTS_AT               = var.starts_at
    B2B_ENDS_AT                 = var.ends_at
    B2B_DOWNLOADS_UNTIL         = var.downloads_until
    B2B_S3_BUCKET               = var.bucket_name
    B2B_CDN_BASE_URL            = var.cdn_base_url
    B2B_AWS_REGION              = var.region
    B2B_GUEST_LIMIT_PER_MINUTE  = tostring(var.guest_limit_per_minute)
    B2B_GUEST_IP_LIMIT          = tostring(var.guest_ip_limit)
    B2B_GUEST_IP_WINDOW_SECONDS = tostring(var.guest_ip_window_seconds)
    B2B_TRUSTED_PROXY_CIDRS     = join(",", aws_subnet.public[*].cidr_block)
  }
  boot_config = {
    environment = local.runtime_environment
    region      = var.region
    secretArn   = var.auth_secret_arn
    image       = var.image_digest_uri
    logGroup    = aws_cloudwatch_log_group.event.name
    memoryMb    = var.container_memory_mb
    runtimeId   = var.runtime_id
  }
  asg_name = var.enable_scheduled_scaling ? aws_autoscaling_group.scheduled[0].name : aws_autoscaling_group.fixed[0].name
}
resource "aws_launch_template" "runtime" {
  name_prefix            = "${local.name}-"
  image_id               = var.ami_id
  instance_type          = var.instance_type
  update_default_version = true
  iam_instance_profile { name = aws_iam_instance_profile.runtime.name }
  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 1 # host networking, no bridge hop
  }
  credit_specification { cpu_credits = var.cpu_credit_mode }
  network_interfaces {
    associate_public_ip_address = true
    security_groups             = [aws_security_group.app.id]
    delete_on_termination       = true
  }
  block_device_mappings {
    device_name = data.aws_ami.runtime.root_device_name
    ebs {
      volume_size           = 20
      volume_type           = "gp3"
      encrypted             = true
      delete_on_termination = true
    }
  }
  user_data = base64encode(templatefile("${path.module}/bootstrap.sh.tftpl", {
    config = base64encode(jsonencode(merge(local.boot_config, { initialize = false })))
  }))
  tag_specifications {
    resource_type = "instance"
    tags          = { Name = local.name, Service = "PeakPicB2B", Environment = var.environment, ProjectId = var.project_id, RuntimeId = var.runtime_id }
  }
  lifecycle {
    precondition {
      condition     = data.aws_ami.runtime.architecture == "x86_64"
      error_message = "t3 instances require the reviewed x86_64 AMI."
    }
  }
}
# Two resources deliberately separate Terraform-owned experiment capacity from schedule-owned production capacity.
# Choose the mode on creation. Switching an active environment's mode replaces its ASG.
resource "aws_autoscaling_group" "fixed" {
  count                     = var.enable_scheduled_scaling ? 0 : 1
  name                      = "${local.name}-fixed"
  min_size                  = var.enable_service ? var.instance_count : 0
  max_size                  = var.enable_service ? var.instance_count : 0
  desired_capacity          = var.enable_service ? var.instance_count : 0
  min_elb_capacity          = var.enable_service ? var.instance_count : 0
  vpc_zone_identifier       = aws_subnet.public[*].id
  target_group_arns         = [aws_lb_target_group.event.arn]
  health_check_type         = "ELB"
  health_check_grace_period = 600
  wait_for_capacity_timeout = "20m"
  launch_template {
    id      = aws_launch_template.runtime.id
    version = aws_launch_template.runtime.latest_version
  }
  instance_refresh {
    strategy = "Rolling"
    preferences { min_healthy_percentage = 50 }
  }
  depends_on = [aws_lb_listener.event, aws_iam_role_policy.runtime]
}
resource "aws_autoscaling_group" "scheduled" {
  count                     = var.enable_scheduled_scaling ? 1 : 0
  name                      = "${local.name}-scheduled"
  min_size                  = 2
  max_size                  = 2
  desired_capacity          = 2
  min_elb_capacity          = 2
  vpc_zone_identifier       = aws_subnet.public[*].id
  target_group_arns         = [aws_lb_target_group.event.arn]
  health_check_type         = "ELB"
  health_check_grace_period = 600
  wait_for_capacity_timeout = "20m"
  launch_template {
    id      = aws_launch_template.runtime.id
    version = aws_launch_template.runtime.latest_version
  }
  instance_refresh {
    strategy = "Rolling"
    preferences { min_healthy_percentage = 50 }
  }
  lifecycle { ignore_changes = [desired_capacity, min_size, max_size] }
  depends_on = [aws_lb_listener.event, aws_iam_role_policy.runtime]
}
resource "aws_autoscaling_schedule" "start" {
  count                  = var.enable_scheduled_scaling ? 1 : 0
  scheduled_action_name  = "${local.name}-event-start"
  autoscaling_group_name = aws_autoscaling_group.scheduled[0].name
  start_time             = timeadd(var.starts_at, "-${var.warmup_minutes}m")
  min_size               = var.instance_count
  max_size               = var.instance_count
  desired_capacity       = var.instance_count
}
resource "aws_autoscaling_schedule" "end" {
  count                  = var.enable_scheduled_scaling ? 1 : 0
  scheduled_action_name  = "${local.name}-event-end"
  autoscaling_group_name = aws_autoscaling_group.scheduled[0].name
  start_time             = var.ends_at
  min_size               = 2
  max_size               = 2
  desired_capacity       = 2 # Retain reads/downloads; does not resize RDS or the instance type.
}
resource "aws_instance" "initializer" {
  count                       = var.enable_database_initializer ? 1 : 0
  ami                         = var.ami_id
  instance_type               = var.instance_type
  subnet_id                   = aws_subnet.public[0].id
  associate_public_ip_address = true
  vpc_security_group_ids      = [aws_security_group.app.id]
  iam_instance_profile        = aws_iam_instance_profile.runtime.name
  credit_specification { cpu_credits = var.cpu_credit_mode }
  metadata_options { http_tokens = "required" }
  root_block_device { encrypted = true }
  user_data_replace_on_change = true
  user_data = templatefile("${path.module}/bootstrap.sh.tftpl", {
    config = base64encode(jsonencode(merge(local.boot_config, { initialize = true })))
  })
  tags       = { Name = "${local.name}-initialize" }
  depends_on = [aws_iam_role_policy.runtime]
}
output "runtime" {
  value = {
    environment             = var.environment, project_id = var.project_id, runtime_id = var.runtime_id, slug = var.slug,
    asg_name                = local.asg_name, alb_dns = aws_lb.event.dns_name,
    initializer_instance_id = one(aws_instance.initializer[*].id), object_prefix = local.prefix, database = local.db_name,
    db_identifier           = aws_db_instance.event.identifier, redis_id = aws_elasticache_replication_group.event.id
  }
}
