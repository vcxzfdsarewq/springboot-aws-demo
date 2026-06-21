variable "name_prefix" {
  description = "リソース名のプレフィックス (例: expense-staging)"
  type        = string
}

variable "vpc_cidr" {
  description = "VPC の CIDR"
  type        = string
  default     = "10.0.0.0/16"
}

variable "azs" {
  description = "使用するアベイラビリティゾーン (2つ)"
  type        = list(string)
}

variable "public_subnet_cidrs" {
  description = "パブリックサブネットの CIDR (AZ ごと)"
  type        = list(string)
  default     = ["10.0.1.0/24", "10.0.2.0/24"]
}

variable "private_subnet_cidrs" {
  description = "プライベートサブネットの CIDR (AZ ごと)"
  type        = list(string)
  default     = ["10.0.11.0/24", "10.0.12.0/24"]
}

variable "single_nat_gateway" {
  description = "true なら NAT Gateway を1つだけ (staging 向けコスト削減)。false なら AZ ごと (本番)"
  type        = bool
  default     = false
}

variable "region" {
  description = "AWS リージョン (VPC エンドポイントのサービス名に使用)"
  type        = string
}

variable "tags" {
  description = "共通タグ"
  type        = map(string)
  default     = {}
}
