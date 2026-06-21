# Terraform state 用のリモートバックエンド (S3 + DynamoDB ロック) を作成する。
# environments/{staging,production} を apply する「前に」一度だけ実行する。
#
# 理由: secrets モジュールの random_password (DB/JWT/Redis の秘密値) は
# Terraform state に平文で保存される。ローカル state ファイルに秘密を残さないため、
# 暗号化された S3 backend を必ず使う。
#
# 使い方:
#   cd terraform/bootstrap
#   terraform init && terraform apply
#   → 出力されたバケット名/テーブル名を各 env の backend "s3" に設定して有効化

terraform {
  required_version = ">= 1.6"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.60"
    }
  }
  # bootstrap 自体の state はローカル (秘密を含まないので可)。
}

provider "aws" {
  region = var.region
}

variable "region" {
  type    = string
  default = "ap-northeast-1"
}

variable "state_bucket_name" {
  description = "state 保存用 S3 バケット名 (グローバル一意)"
  type        = string
}

variable "lock_table_name" {
  type    = string
  default = "expense-tflock"
}

resource "aws_s3_bucket" "state" {
  bucket = var.state_bucket_name
}

resource "aws_s3_bucket_versioning" "state" {
  bucket = aws_s3_bucket.state.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "state" {
  bucket = aws_s3_bucket.state.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "aws:kms"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "state" {
  bucket                  = aws_s3_bucket.state.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_dynamodb_table" "lock" {
  name         = var.lock_table_name
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "LockID"
  attribute {
    name = "LockID"
    type = "S"
  }
}

output "state_bucket" {
  value = aws_s3_bucket.state.id
}

output "lock_table" {
  value = aws_dynamodb_table.lock.name
}
