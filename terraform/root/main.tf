# 全モジュールを配線する root 構成。
# environments/{staging,production} から source = "../../root" で呼ぶ。

locals {
  common_tags = merge(var.tags, {
    Project   = "expense-api"
    ManagedBy = "terraform"
  })
}

module "network" {
  source = "../modules/network"

  name_prefix        = var.name_prefix
  azs                = var.azs
  single_nat_gateway = var.single_nat_gateway
  region             = var.region
  tags               = local.common_tags
}

module "security" {
  source = "../modules/security"

  name_prefix = var.name_prefix
  vpc_id      = module.network.vpc_id
  vpc_cidr    = module.network.vpc_cidr
  tags        = local.common_tags
}

module "endpoints" {
  source = "../modules/endpoints"

  name_prefix        = var.name_prefix
  region             = var.region
  vpc_id             = module.network.vpc_id
  private_subnet_ids = module.network.private_subnet_ids
  security_group_id  = module.security.vpce_sg_id
  tags               = local.common_tags
}

module "s3" {
  source = "../modules/s3"

  bucket_name = var.receipts_bucket_name
  tags        = local.common_tags
}

module "ecr" {
  source = "../modules/ecr"

  repository_name = "expense-api"
  tags            = local.common_tags
}

module "secrets" {
  source = "../modules/secrets"

  name_prefix = var.name_prefix
  tags        = local.common_tags
}

module "iam" {
  source = "../modules/iam"

  name_prefix         = var.name_prefix
  secret_arns         = module.secrets.secret_arns
  receipts_bucket_arn = module.s3.bucket_arn
  tags                = local.common_tags
}

module "rds" {
  source = "../modules/rds"

  name_prefix         = var.name_prefix
  private_subnet_ids  = module.network.private_subnet_ids
  security_group_id   = module.security.rds_sg_id
  instance_class      = var.rds_instance_class
  multi_az            = var.rds_multi_az
  db_password         = module.secrets.db_password_value
  deletion_protection = var.deletion_protection
  tags                = local.common_tags
}

module "elasticache" {
  source = "../modules/elasticache"

  name_prefix        = var.name_prefix
  private_subnet_ids = module.network.private_subnet_ids
  security_group_id  = module.security.redis_sg_id
  node_type          = var.redis_node_type
  multi_az           = var.redis_multi_az
  auth_token         = module.secrets.redis_auth_value
  tags               = local.common_tags
}

module "alb" {
  source = "../modules/alb"

  name_prefix         = var.name_prefix
  vpc_id              = module.network.vpc_id
  public_subnet_ids   = module.network.public_subnet_ids
  security_group_id   = module.security.alb_sg_id
  acm_certificate_arn = var.acm_certificate_arn
  tags                = local.common_tags
}

module "ecs" {
  source = "../modules/ecs"

  name_prefix        = var.name_prefix
  region             = var.region
  private_subnet_ids = module.network.private_subnet_ids
  security_group_id  = module.security.ecs_sg_id
  target_group_arn   = module.alb.target_group_arn
  execution_role_arn = module.iam.execution_role_arn
  task_role_arn      = module.iam.task_role_arn

  container_image      = var.container_image
  spring_profile       = var.spring_profile
  desired_count        = var.ecs_desired_count
  min_capacity         = var.ecs_desired_count
  jdbc_url             = module.rds.jdbc_url
  redis_host           = module.elasticache.primary_endpoint
  s3_bucket            = module.s3.bucket_name
  cors_allowed_origins = var.cors_allowed_origins

  jwt_secret_arn  = module.secrets.jwt_secret_arn
  db_password_arn = module.secrets.db_password_arn
  redis_auth_arn  = module.secrets.redis_auth_arn

  tags = local.common_tags

  # ECS Service 作成時、Target Group が ALB の Listener に紐付け済みである必要がある
  # (未紐付けだと "target group does not have an associated load balancer")。
  depends_on = [module.alb]
}

module "waf" {
  source = "../modules/waf"

  name_prefix = var.name_prefix
  alb_arn     = module.alb.alb_arn
  tags        = local.common_tags
}
