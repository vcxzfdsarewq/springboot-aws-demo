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
  # 先に terraform/bootstrap を apply してバケット/テーブルを作成してから、以下を有効化:
  # backend "s3" {
  #   bucket         = "expense-tfstate-<アカウントID>"
  #   key            = "staging/terraform.tfstate"
  #   region         = "ap-northeast-1"
  #   dynamodb_table = "expense-tflock"
  #   encrypt        = true
  # }
}

provider "aws" {
  region = var.region
  default_tags {
    tags = {
      Environment = "staging"
    }
  }
}

variable "region" {
  type    = string
  default = "ap-northeast-1"
}

variable "container_image" {
  description = "ECR イメージ URI。ECR は IMMUTABLE タグのため :latest 不可。git SHA / タイムスタンプの一意タグを使う"
  type        = string
}

variable "receipts_bucket_name" {
  type = string
}

module "stack" {
  source = "../../root"

  name_prefix = "expense-staging"
  region      = var.region
  azs         = ["ap-northeast-1a", "ap-northeast-1c"]

  # staging: コスト優先 (NAT 1つ・single-AZ・小サイズ・削除保護なし)
  single_nat_gateway  = true
  rds_instance_class  = "db.t4g.medium"
  rds_multi_az        = false
  redis_node_type     = "cache.t4g.small"
  redis_multi_az      = false
  ecs_desired_count   = 1
  deletion_protection = false

  container_image      = var.container_image
  spring_profile       = "staging"
  receipts_bucket_name = var.receipts_bucket_name

  tags = { Environment = "staging" }
}

output "alb_dns_name" {
  value = module.stack.alb_dns_name
}

output "ecr_repository_url" {
  value = module.stack.ecr_repository_url
}
