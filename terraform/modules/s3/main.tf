# 領収書バケット: 非公開 + SSE 暗号化 + 非TLS拒否
# 設計: requirements.md 4.2

variable "bucket_name" {
  type = string
}

variable "tags" {
  type    = map(string)
  default = {}
}

resource "aws_s3_bucket" "receipts" {
  bucket = var.bucket_name
  tags   = var.tags
}

# Block Public Access を全面有効化
resource "aws_s3_bucket_public_access_block" "receipts" {
  bucket                  = aws_s3_bucket.receipts.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# 保存時暗号化 (SSE-S3)。KMS にする場合は sse_algorithm を aws:kms に変更
resource "aws_s3_bucket_server_side_encryption_configuration" "receipts" {
  bucket = aws_s3_bucket.receipts.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_versioning" "receipts" {
  bucket = aws_s3_bucket.receipts.id
  versioning_configuration {
    status = "Enabled"
  }
}

# 非TLS (HTTP) アクセスを拒否
resource "aws_s3_bucket_policy" "deny_insecure" {
  bucket = aws_s3_bucket.receipts.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "DenyInsecureTransport"
      Effect    = "Deny"
      Principal = "*"
      Action    = "s3:*"
      Resource = [
        aws_s3_bucket.receipts.arn,
        "${aws_s3_bucket.receipts.arn}/*"
      ]
      Condition = {
        Bool = { "aws:SecureTransport" = "false" }
      }
    }]
  })
}

output "bucket_name" {
  value = aws_s3_bucket.receipts.id
}

output "bucket_arn" {
  value = aws_s3_bucket.receipts.arn
}
