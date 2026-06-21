output "alb_dns_name" {
  description = "ALB の DNS 名 (Route53 で CNAME を向ける)"
  value       = module.alb.alb_dns_name
}

output "ecr_repository_url" {
  value = module.ecr.repository_url
}

output "ecs_cluster_name" {
  value = module.ecs.cluster_name
}

output "ecs_service_name" {
  value = module.ecs.service_name
}

output "rds_endpoint" {
  value = module.rds.endpoint
}

output "redis_endpoint" {
  value = module.elasticache.primary_endpoint
}

output "receipts_bucket" {
  value = module.s3.bucket_name
}

output "log_group" {
  value = module.ecs.log_group
}
