# GitHub Actions 用の OIDC プロバイダ + デプロイロール。
# 長期アクセスキーを使わず CI から AWS へアクセスする一回限りのセットアップ。
#
# ロールは用途・環境別に最小権限で分離する:
#   - build    : main ブランチからのみ assume。ECR push/pull のみ
#   - staging  : environment:staging からのみ assume。staging の ECS だけ更新可
#   - prod     : environment:production からのみ assume。prod の ECS だけ更新可
#
# 使い方:
#   cd terraform/cicd
#   terraform init
#   terraform apply -var="github_org=YOUR_ORG" -var="github_repo=springboot-aws-demo"
#   → 出力の *_role_arn を GitHub の Variables に設定 (docs/cicd.md 参照)

terraform {
  required_version = ">= 1.6"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.60"
    }
  }
}

provider "aws" {
  region = var.region
}

variable "region" {
  type    = string
  default = "ap-northeast-1"
}

variable "github_org" {
  type = string
}

variable "github_repo" {
  type = string
}

variable "name_prefix" {
  type    = string
  default = "expense"
}

data "aws_caller_identity" "current" {}

locals {
  account_id = data.aws_caller_identity.current.account_id
  repo_sub   = "repo:${var.github_org}/${var.github_repo}"

  # 環境ごとの ECS 命名 (ecs モジュールの "${name_prefix}-cluster|app" と一致)
  envs = {
    staging = {
      gh_environment = "staging"
      ecs_prefix     = "${var.name_prefix}-staging"
    }
    production = {
      gh_environment = "production"
      ecs_prefix     = "${var.name_prefix}-prod"
    }
  }
}

# GitHub の OIDC プロバイダ (アカウントに1つ)
resource "aws_iam_openid_connect_provider" "github" {
  url             = "https://token.actions.githubusercontent.com"
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = ["6938fd4d98bab03faadb97b34396831e3780aea1"]
}

# ============================================================
# build ロール: main ブランチからのみ。ECR push/pull のみ。
# ============================================================
data "aws_iam_policy_document" "build_assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["${local.repo_sub}:ref:refs/heads/main"]
    }
  }
}

resource "aws_iam_role" "build" {
  name               = "${var.name_prefix}-gha-build"
  assume_role_policy = data.aws_iam_policy_document.build_assume.json
}

data "aws_iam_policy_document" "build" {
  statement {
    sid       = "EcrAuth"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }
  statement {
    sid = "EcrPushPull"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:CompleteLayerUpload",
      "ecr:InitiateLayerUpload",
      "ecr:PutImage",
      "ecr:UploadLayerPart",
      "ecr:BatchGetImage",
      "ecr:GetDownloadUrlForLayer",
    ]
    resources = ["arn:aws:ecr:${var.region}:${local.account_id}:repository/${var.name_prefix}-api"]
  }
}

resource "aws_iam_role_policy" "build" {
  name   = "${var.name_prefix}-gha-build"
  role   = aws_iam_role.build.id
  policy = data.aws_iam_policy_document.build.json
}

# ============================================================
# deploy ロール (staging / production): environment 別に分離。
# ============================================================
data "aws_iam_policy_document" "deploy_assume" {
  for_each = local.envs
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }
    # その GitHub Environment のジョブのみ assume 可能
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["${local.repo_sub}:environment:${each.value.gh_environment}"]
    }
  }
}

resource "aws_iam_role" "deploy" {
  for_each           = local.envs
  name               = "${var.name_prefix}-gha-deploy-${each.key}"
  assume_role_policy = data.aws_iam_policy_document.deploy_assume[each.key].json
}

data "aws_iam_policy_document" "deploy" {
  for_each = local.envs

  # RegisterTaskDefinition / DescribeTaskDefinition は resource-level 非対応 → *
  statement {
    sid       = "EcsTaskDef"
    actions   = ["ecs:RegisterTaskDefinition", "ecs:DescribeTaskDefinition"]
    resources = ["*"]
  }

  # サービス操作は当該環境の cluster/service に限定
  statement {
    sid     = "EcsService"
    actions = ["ecs:DescribeServices", "ecs:UpdateService"]
    resources = [
      "arn:aws:ecs:${var.region}:${local.account_id}:service/${each.value.ecs_prefix}-cluster/${each.value.ecs_prefix}-app"
    ]
  }

  # PassRole は当該環境のタスク/実行ロールのみ
  statement {
    sid       = "PassRoles"
    actions   = ["iam:PassRole"]
    resources = ["arn:aws:iam::${local.account_id}:role/${each.value.ecs_prefix}-*"]
    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role_policy" "deploy" {
  for_each = local.envs
  name     = "${var.name_prefix}-gha-deploy-${each.key}"
  role     = aws_iam_role.deploy[each.key].id
  policy   = data.aws_iam_policy_document.deploy[each.key].json
}

# ============================================================
# 出力 (GitHub Variables に設定する値)
# ============================================================
output "build_role_arn" {
  description = "repo レベル Variable AWS_BUILD_ROLE_ARN に設定"
  value       = aws_iam_role.build.arn
}

output "staging_deploy_role_arn" {
  description = "staging Environment の Variable AWS_ROLE_ARN に設定"
  value       = aws_iam_role.deploy["staging"].arn
}

output "production_deploy_role_arn" {
  description = "production Environment の Variable AWS_ROLE_ARN に設定"
  value       = aws_iam_role.deploy["production"].arn
}
