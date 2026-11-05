terraform {
  backend "s3" {}
  required_version = ">= 1.10, < 2.0"
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 6.0" }
  }
}

variable "account_id" { type = string }
variable "region" {
  type    = string
  default = "ap-northeast-2"
}
variable "bucket_name" {
  type = string
  validation {
    condition     = can(regex("^peakpic-b2b-[a-z0-9-]{3,50}$", var.bucket_name))
    error_message = "Create a NEW B2B bucket; existing B2C buckets are not accepted."
  }
}

provider "aws" {
  region              = var.region
  allowed_account_ids = [var.account_id]
  default_tags { tags = { Service = "PeakPicB2B", ManagedBy = "Terraform" } }
}

resource "aws_s3_bucket" "b2b" {
  bucket        = var.bucket_name
  force_destroy = false
  lifecycle { prevent_destroy = true }
}
resource "aws_s3_bucket_public_access_block" "b2b" {
  bucket                  = aws_s3_bucket.b2b.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}
resource "aws_s3_bucket_server_side_encryption_configuration" "b2b" {
  bucket = aws_s3_bucket.b2b.id
  rule {
    apply_server_side_encryption_by_default { sse_algorithm = "AES256" }
  }
}
resource "aws_s3_bucket_ownership_controls" "b2b" {
  bucket = aws_s3_bucket.b2b.id
  rule { object_ownership = "BucketOwnerEnforced" }
}
resource "aws_s3_bucket_cors_configuration" "b2b" {
  bucket = aws_s3_bucket.b2b.id
  cors_rule {
    allowed_methods = ["GET", "HEAD"]
    allowed_origins = ["*"]
    allowed_headers = ["*"]
  }
}
# Per-project retention is handled by the fleet controller, not a bucket-wide 7-day expiry.
# Keep only abandoned multipart uploads in the shared bucket lifecycle.
resource "aws_s3_bucket_lifecycle_configuration" "b2b" {
  bucket = aws_s3_bucket.b2b.id
  rule {
    id     = "abort-incomplete-uploads"
    status = "Enabled"
    filter { prefix = "b2b/" }
    abort_incomplete_multipart_upload { days_after_initiation = 1 }
  }
}
resource "aws_cloudfront_origin_access_control" "b2b" {
  name                              = var.bucket_name
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}
resource "aws_cloudfront_function" "cors" {
  name    = "pp-b2b-${substr(sha256(var.bucket_name), 0, 20)}-cors"
  runtime = "cloudfront-js-2.0"
  publish = true
  code    = <<-JS
    function handler(event) {
      var response = event.response;
      response.headers['access-control-allow-origin'] = {value: '*'};
      return response;
    }
  JS
}
resource "aws_cloudfront_distribution" "b2b" {
  enabled = true
  origin {
    domain_name              = aws_s3_bucket.b2b.bucket_regional_domain_name
    origin_id                = "b2b-images"
    origin_access_control_id = aws_cloudfront_origin_access_control.b2b.id
  }
  # Photos remain capability URLs (random image ID). Disable edge caching so
  # storage deletion is not followed by a stale CDN copy; access is revoked by deletion.
  default_cache_behavior {
    target_origin_id       = "b2b-images"
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD"]
    cached_methods         = ["GET", "HEAD"]
    cache_policy_id        = "413f160d-3fce-4c47-a57c-9b19ec24381e" # Managed-CachingDisabled
    function_association {
      event_type   = "viewer-response"
      function_arn = aws_cloudfront_function.cors.arn
    }
  }
  restrictions {
    geo_restriction { restriction_type = "none" }
  }
  viewer_certificate { cloudfront_default_certificate = true }
  lifecycle { prevent_destroy = true }
}
resource "aws_s3_bucket_policy" "b2b" {
  bucket = aws_s3_bucket.b2b.id
  policy = jsonencode({ Version = "2012-10-17", Statement = [
    {
      Sid       = "CloudFrontReadOnly", Effect = "Allow", Principal = { Service = "cloudfront.amazonaws.com" },
      Action    = "s3:GetObject", Resource = "${aws_s3_bucket.b2b.arn}/b2b/*",
      Condition = { StringEquals = { "AWS:SourceArn" = aws_cloudfront_distribution.b2b.arn } }
    },
    {
      Sid       = "DenyInsecureTransport", Effect = "Deny", Principal = "*", Action = "s3:*",
      Resource  = [aws_s3_bucket.b2b.arn, "${aws_s3_bucket.b2b.arn}/*"],
      Condition = { Bool = { "aws:SecureTransport" = "false" } }
    }
  ] })
}
output "bucket_name" { value = aws_s3_bucket.b2b.id }
output "cdn_base_url" { value = "https://${aws_cloudfront_distribution.b2b.domain_name}" }
resource "aws_ecr_repository" "b2b" {
  name                 = "peakpic-b2b-server"
  image_tag_mutability = "IMMUTABLE"
  image_scanning_configuration { scan_on_push = true }
  lifecycle { prevent_destroy = true }
}
output "ecr_repository_url" { value = aws_ecr_repository.b2b.repository_url }
