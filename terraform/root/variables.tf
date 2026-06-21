variable "name_prefix" {
  description = "リソース名プレフィックス (例: expense-staging)"
  type        = string
}

variable "region" {
  type    = string
  default = "ap-northeast-1"
}

variable "azs" {
  type = list(string)
}

variable "single_nat_gateway" {
  type    = bool
  default = false
}

variable "container_image" {
  description = "ECS が起動する ECR イメージ URI"
  type        = string
}

variable "spring_profile" {
  type    = string
  default = "prod"
}

# --- 環境ごとのサイズ ---
variable "rds_instance_class" {
  type    = string
  default = "db.t4g.medium"
}

variable "rds_multi_az" {
  type    = bool
  default = false
}

variable "redis_node_type" {
  type    = string
  default = "cache.t4g.small"
}

variable "redis_multi_az" {
  type    = bool
  default = false
}

variable "ecs_desired_count" {
  type    = number
  default = 2
}

variable "deletion_protection" {
  type    = bool
  default = true
}

variable "acm_certificate_arn" {
  type    = string
  default = ""
}

variable "cors_allowed_origins" {
  type    = string
  default = ""
}

variable "receipts_bucket_name" {
  description = "領収書バケット名 (グローバル一意)"
  type        = string
}

variable "tags" {
  type    = map(string)
  default = {}
}
