# Secrets Manager: JWT 秘密鍵・DB パスワード・Redis 認証トークン
# 値はランダム生成し、ECS タスク定義から secrets として注入する。

variable "name_prefix" {
  type = string
}

variable "tags" {
  type    = map(string)
  default = {}
}

# --- ランダム値の生成 ---
resource "random_password" "jwt_secret" {
  length  = 48
  special = false
}

resource "random_password" "db_password" {
  length  = 32
  special = false # RDS パスワードに使えない文字を避ける
}

resource "random_password" "redis_auth" {
  length  = 32
  special = false
}

# --- Secrets ---
resource "aws_secretsmanager_secret" "jwt_secret" {
  name        = "${var.name_prefix}/jwt-secret"
  description = "JWT signing secret (HS384)"
  tags        = var.tags
}

resource "aws_secretsmanager_secret_version" "jwt_secret" {
  secret_id     = aws_secretsmanager_secret.jwt_secret.id
  secret_string = random_password.jwt_secret.result
}

resource "aws_secretsmanager_secret" "db_password" {
  name        = "${var.name_prefix}/db-password"
  description = "RDS master password"
  tags        = var.tags
}

resource "aws_secretsmanager_secret_version" "db_password" {
  secret_id     = aws_secretsmanager_secret.db_password.id
  secret_string = random_password.db_password.result
}

resource "aws_secretsmanager_secret" "redis_auth" {
  name        = "${var.name_prefix}/redis-auth-token"
  description = "ElastiCache Redis AUTH token"
  tags        = var.tags
}

resource "aws_secretsmanager_secret_version" "redis_auth" {
  secret_id     = aws_secretsmanager_secret.redis_auth.id
  secret_string = random_password.redis_auth.result
}

output "jwt_secret_arn" {
  value = aws_secretsmanager_secret.jwt_secret.arn
}

output "db_password_arn" {
  value = aws_secretsmanager_secret.db_password.arn
}

output "db_password_value" {
  value     = random_password.db_password.result
  sensitive = true
}

output "redis_auth_arn" {
  value = aws_secretsmanager_secret.redis_auth.arn
}

output "redis_auth_value" {
  value     = random_password.redis_auth.result
  sensitive = true
}

output "secret_arns" {
  description = "ECS タスクが読み取る必要のある全シークレット ARN"
  value = [
    aws_secretsmanager_secret.jwt_secret.arn,
    aws_secretsmanager_secret.db_password.arn,
    aws_secretsmanager_secret.redis_auth.arn,
  ]
}
