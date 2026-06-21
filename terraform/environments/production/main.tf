terraform {
  required_version = ">= 1.6"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.60"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }

  # 【apply 前に必須】state には秘密値が平文で入るため、暗号化 S3 backend を有効化する。
  # 先に terraform/bootstrap を apply してから、以下を有効化:
  # backend "s3" {
  #   bucket         = "expense-tfstate-<アカウントID>"
  #   key            = "production/terraform.tfstate"
  #   region         = "ap-northeast-1"
  #   dynamodb_table = "expense-tflock"
  #   encrypt        = true
  # }
}

provider "aws" {
  region = var.region
  default_tags {
    tags = {
      Environment = "production"
    }
  }
}

variable "region" {
  type    = string
  default = "ap-northeast-1"
}

variable "container_image" {
  type = string
}

variable "receipts_bucket_name" {
  type = string
}

variable "acm_certificate_arn" {
  description = "本番は HTTPS 必須。ACM 証明書 ARN を指定"
  type        = string
}

variable "cors_allowed_origins" {
  type    = string
  default = ""
}

module "stack" {
  source = "../../root"

  name_prefix = "expense-prod"
  region      = var.region
  azs         = ["ap-northeast-1a", "ap-northeast-1c"]

  # production: 可用性優先 (NAT を AZ ごと・Multi-AZ・削除保護・2タスク)
  single_nat_gateway  = false
  rds_instance_class  = "db.r6g.large"
  rds_multi_az        = true
  redis_node_type     = "cache.r6g.large"
  redis_multi_az      = true
  ecs_desired_count   = 2
  deletion_protection = true

  container_image      = var.container_image
  spring_profile       = "prod"
  acm_certificate_arn  = var.acm_certificate_arn
  cors_allowed_origins = var.cors_allowed_origins
  receipts_bucket_name = var.receipts_bucket_name

  tags = { Environment = "production" }
}

output "alb_dns_name" {
  value = module.stack.alb_dns_name
}

output "ecr_repository_url" {
  value = module.stack.ecr_repository_url
}
