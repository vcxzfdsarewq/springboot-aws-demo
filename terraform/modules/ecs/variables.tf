variable "name_prefix" {
  type = string
}

variable "region" {
  type = string
}

variable "private_subnet_ids" {
  type = list(string)
}

variable "security_group_id" {
  type = string
}

variable "target_group_arn" {
  type = string
}

variable "execution_role_arn" {
  type = string
}

variable "task_role_arn" {
  type = string
}

variable "container_image" {
  description = "ECR イメージ URI (例: <acct>.dkr.ecr.<region>.amazonaws.com/expense-api:<tag>)"
  type        = string
}

variable "app_port" {
  type    = number
  default = 8080
}

variable "cpu" {
  type    = number
  default = 1024
}

variable "memory" {
  type    = number
  default = 2048
}

variable "desired_count" {
  type    = number
  default = 2
}

variable "spring_profile" {
  type    = string
  default = "prod"
}

variable "jdbc_url" {
  type = string
}

variable "db_username" {
  type    = string
  default = "expense"
}

variable "redis_host" {
  type = string
}

variable "redis_port" {
  type    = number
  default = 6379
}

variable "s3_bucket" {
  type = string
}

variable "cors_allowed_origins" {
  type    = string
  default = ""
}

# Secrets Manager ARN (タスク定義の secrets に注入)
variable "jwt_secret_arn" {
  type = string
}

variable "db_password_arn" {
  type = string
}

variable "redis_auth_arn" {
  type = string
}

variable "log_retention_days" {
  type    = number
  default = 30
}

variable "min_capacity" {
  type    = number
  default = 2
}

variable "max_capacity" {
  type    = number
  default = 6
}

variable "tags" {
  type    = map(string)
  default = {}
}
