# ElastiCache for Redis: Rate Limit / Refresh リプレイの共有ストア
# TLS + AUTH 必須・Private Subnet 限定 (設計: requirements.md 3.4)

variable "name_prefix" {
  type = string
}

variable "private_subnet_ids" {
  type = list(string)
}

variable "security_group_id" {
  type = string
}

variable "node_type" {
  type    = string
  default = "cache.t4g.small"
}

variable "multi_az" {
  type    = bool
  default = false
}

variable "auth_token" {
  type      = string
  sensitive = true
}

variable "tags" {
  type    = map(string)
  default = {}
}

resource "aws_elasticache_subnet_group" "this" {
  name       = "${var.name_prefix}-redis-subnet"
  subnet_ids = var.private_subnet_ids
  tags       = var.tags
}

resource "aws_elasticache_replication_group" "this" {
  replication_group_id = "${var.name_prefix}-redis"
  description          = "Rate limit / refresh replay shared store"

  engine         = "redis"
  engine_version = "7.1"
  node_type      = var.node_type
  port           = 6379

  # cluster mode disabled。Multi-AZ 時はレプリカ + 自動フェイルオーバー
  num_cache_clusters         = var.multi_az ? 2 : 1
  automatic_failover_enabled = var.multi_az
  multi_az_enabled           = var.multi_az

  subnet_group_name  = aws_elasticache_subnet_group.this.name
  security_group_ids = [var.security_group_id]

  # 暗号化 + AUTH (リプレイキャッシュに平文トークンを短命保持するため必須)
  at_rest_encryption_enabled = true
  transit_encryption_enabled = true
  auth_token                 = var.auth_token

  # リプレイキャッシュの平文をスナップショットに残さない
  snapshot_retention_limit = 0

  apply_immediately = false
  tags              = var.tags
}

output "primary_endpoint" {
  value = aws_elasticache_replication_group.this.primary_endpoint_address
}

output "port" {
  value = aws_elasticache_replication_group.this.port
}
