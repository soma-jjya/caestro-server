terraform {
  backend "s3" {}
  required_version = ">= 1.10, < 2.0"
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 6.0" }
  }
}
variable "account_id" { type = string }
variable "region" { type = string }
variable "availability_zones" {
  type = list(string)
  validation {
    condition     = length(var.availability_zones) == 2 && length(distinct(var.availability_zones)) == 2
    error_message = "Two distinct availability zones are required."
  }
}
variable "environment" {
  type = string
  validation {
    condition     = contains(["test", "prod"], var.environment)
    error_message = "environment must be test or prod"
  }
}
variable "project_id" {
  type = string
  validation {
    condition     = can(regex("^bp_[a-z0-9]{4,32}$", var.project_id))
    error_message = "invalid project ID"
  }
}
variable "slug" {
  type = string
  validation {
    condition     = can(regex("^[a-z0-9-]{3,40}$", var.slug)) && !contains(["b2b", "go", "fill", "admin", "auth", "api", "login", "assets", "static", "frames", "catalog", "www", "app", "health", "studio", "projects"], var.slug)
    error_message = "invalid slug"
  }
}
variable "runtime_id" {
  type = string
  validation {
    condition     = can(regex("^[a-z0-9]{12}$", var.runtime_id))
    error_message = "Use a fresh 12-character runtime ID for each new database."
  }
}
variable "api_base_url" {
  type = string
  validation {
    condition     = can(regex("^https://[a-z0-9.-]+$", var.api_base_url)) && var.api_base_url != "https://api.peakpic.app"
    error_message = "Use a separate B2B HTTPS API origin without trailing slash."
  }
}
variable "starts_at" {
  type = string
  validation {
    condition     = can(formatdate("YYYY", var.starts_at)) && can(regex("Z$", var.starts_at))
    error_message = "Use a UTC RFC3339 timestamp ending in Z for ASG scheduling."
  }
}
variable "ends_at" {
  type = string
  validation {
    condition     = try(timecmp(var.ends_at, var.starts_at) > 0, false) && can(regex("Z$", var.ends_at))
    error_message = "End must be after start, in UTC ending in Z."
  }
}
variable "downloads_until" {
  type = string
  validation {
    condition     = try(timecmp(var.downloads_until, var.ends_at) > 0, false)
    error_message = "Retention must end after the event."
  }
}
variable "bucket_name" {
  type = string
  validation {
    condition     = can(regex("^peakpic-b2b-[a-z0-9-]{3,50}$", var.bucket_name))
    error_message = "Only the dedicated B2B bucket is accepted."
  }
}
variable "cdn_base_url" {
  type = string
  validation {
    condition     = can(regex("^https://[a-z0-9.-]+$", var.cdn_base_url)) && var.cdn_base_url != "https://di519bna44kn1.cloudfront.net"
    error_message = "Use the separate B2B HTTPS CDN origin without trailing slash."
  }
}
variable "image_digest_uri" {
  type = string
  validation {
    condition     = can(regex("/peakpic-b2b-server@sha256:[a-f0-9]{64}$", var.image_digest_uri))
    error_message = "Pin a digest from the separate peakpic-b2b-server ECR repository."
  }
}
variable "certificate_arn" { type = string }
variable "auth_secret_arn" {
  type = string
  validation {
    condition     = can(regex(":secret:peakpic-b2b/", var.auth_secret_arn))
    error_message = "Use a separate B2B secret containing jwtSecret, adminKey and dbPassword."
  }
}
variable "enable_service" {
  type    = bool
  default = false
}
variable "enable_database_initializer" {
  type    = bool
  default = false
  validation {
    condition     = !var.enable_database_initializer || !var.enable_service
    error_message = "Initialize the new empty database before enabling the service."
  }
}
variable "ami_id" {
  type        = string
  description = "Pinned x86_64 AL2023 AMI with Docker, Python3, AWS CLI, SSM and CloudWatch agents already installed."
}
variable "instance_type" {
  type    = string
  default = "t3.micro"
  validation {
    condition     = contains(["t3.micro", "t3.small", "t3.medium"], var.instance_type)
    error_message = "Start at t3.micro; resize only after recording a measured bottleneck."
  }
}
variable "instance_count" {
  type    = number
  default = 2
  validation {
    condition     = var.instance_count >= 2 && var.instance_count <= 20 && floor(var.instance_count) == var.instance_count
    error_message = "Use an explicit fixed test/event count from 2 to 20."
  }
}
variable "cpu_credit_mode" {
  type = string
  validation {
    condition     = contains(["standard", "unlimited"], var.cpu_credit_mode)
    error_message = "Match production credit mode; do not infer it from instance type."
  }
}
variable "container_memory_mb" {
  type        = number
  description = "Match the production container/JVM memory budget, leaving room for Docker, agents and the OS."
  validation {
    condition     = var.container_memory_mb >= 256 && var.container_memory_mb <= lookup({ "t3.micro" = 768, "t3.small" = 1536, "t3.medium" = 3072 }, var.instance_type, 0)
    error_message = "Container memory must leave host headroom."
  }
}
variable "db_pool_size" {
  type = number
  validation {
    condition     = var.db_pool_size >= 1 && var.db_pool_size <= 100 && floor(var.db_pool_size) == var.db_pool_size
    error_message = "Set the observed per-process production pool size."
  }
}
variable "enable_scheduled_scaling" {
  type    = bool
  default = false
  validation {
    condition     = !var.enable_scheduled_scaling || (var.environment == "prod" && var.enable_service)
    error_message = "Only an enabled B2B production service may use event schedules."
  }
}
variable "warmup_minutes" {
  type    = number
  default = 15
  validation {
    condition     = var.warmup_minutes >= 10 && var.warmup_minutes <= 120 && floor(var.warmup_minutes) == var.warmup_minutes
    error_message = "Allow 10..120 minutes to start and verify event capacity."
  }
}
variable "db_instance_class" { type = string }
variable "db_engine_version" { type = string }
variable "db_multi_az" { type = bool }
variable "db_allocated_storage_gb" { type = number }
variable "db_storage_type" {
  type = string
  validation {
    condition     = contains(["gp2", "gp3"], var.db_storage_type)
    error_message = "Match the observed gp2/gp3 baseline."
  }
}
variable "db_max_storage_gb" {
  type = number
  validation {
    condition     = var.db_allocated_storage_gb >= 20 && var.db_max_storage_gb <= 1000 && (var.db_max_storage_gb == 0 || var.db_max_storage_gb >= ceil(var.db_allocated_storage_gb * 1.1))
    error_message = "Use 0 to disable storage autoscaling, or a ceiling at least 10% above allocated storage (up to 1000 GiB)."
  }
}
variable "redis_node_type" { type = string }
variable "redis_engine_version" { type = string }
variable "redis_node_count" {
  type = number
  validation {
    condition     = contains([1, 2], var.redis_node_count)
    error_message = "Match one node or primary plus replica."
  }
}
variable "redis_multi_az" {
  type = bool
  validation {
    condition     = !var.redis_multi_az || var.redis_node_count == 2
    error_message = "Multi-AZ requires a replica."
  }
}
variable "guest_ip_limit" {
  type    = number
  default = 30
  validation {
    condition     = var.guest_ip_limit >= 1 && var.guest_ip_limit <= 10000 && floor(var.guest_ip_limit) == var.guest_ip_limit
    error_message = "Guest IP quota must be an explicit integer from 1 to 10000."
  }
}
variable "guest_ip_window_seconds" {
  type    = number
  default = 60
  validation {
    condition     = var.guest_ip_window_seconds >= 1 && var.guest_ip_window_seconds <= 3600 && floor(var.guest_ip_window_seconds) == var.guest_ip_window_seconds
    error_message = "Guest IP window must be 1..3600 seconds."
  }
}
variable "guest_limit_per_minute" {
  type = number
  validation {
    condition     = var.guest_limit_per_minute > 0 && var.guest_limit_per_minute <= 10000 && floor(var.guest_limit_per_minute) == var.guest_limit_per_minute
    error_message = "Guest issue rate must match the reviewed test profile."
  }
}
