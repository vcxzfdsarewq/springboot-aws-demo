# Interface VPC エンドポイント (ECR api/dkr, Secrets Manager, CloudWatch Logs)
# network と security の循環依存を避けるため分離。

variable "name_prefix" {
  type = string
}

variable "region" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "private_subnet_ids" {
  type = list(string)
}

variable "security_group_id" {
  description = "Interface エンドポイント用 SG (ECS から 443 を許可)"
  type        = string
}

variable "tags" {
  type    = map(string)
  default = {}
}

locals {
  interface_endpoints = {
    ecr_api    = "com.amazonaws.${var.region}.ecr.api"
    ecr_dkr    = "com.amazonaws.${var.region}.ecr.dkr"
    secretsmgr = "com.amazonaws.${var.region}.secretsmanager"
    logs       = "com.amazonaws.${var.region}.logs"
  }
}

resource "aws_vpc_endpoint" "interface" {
  for_each            = local.interface_endpoints
  vpc_id              = var.vpc_id
  service_name        = each.value
  vpc_endpoint_type   = "Interface"
  subnet_ids          = var.private_subnet_ids
  security_group_ids  = [var.security_group_id]
  private_dns_enabled = true
  tags                = merge(var.tags, { Name = "${var.name_prefix}-vpce-${each.key}" })
}
