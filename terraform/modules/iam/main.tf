# ECS タスク実行ロール + タスクロール
# 実行ロール: ECR pull / Logs / Secrets 注入。タスクロール: S3 領収書アクセス。

variable "name_prefix" {
  type = string
}

variable "secret_arns" {
  description = "実行ロールが読み取るシークレット ARN"
  type        = list(string)
}

variable "receipts_bucket_arn" {
  description = "タスクロールがアクセスする領収書バケット ARN"
  type        = string
}

variable "tags" {
  type    = map(string)
  default = {}
}

data "aws_iam_policy_document" "ecs_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

# --- 実行ロール (ECS エージェントが使用) ---
resource "aws_iam_role" "execution" {
  name               = "${var.name_prefix}-ecs-exec"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
  tags               = var.tags
}

resource "aws_iam_role_policy_attachment" "execution_managed" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# Secrets 注入の許可
data "aws_iam_policy_document" "secrets_read" {
  statement {
    actions   = ["secretsmanager:GetSecretValue"]
    resources = var.secret_arns
  }
}

resource "aws_iam_role_policy" "execution_secrets" {
  name   = "${var.name_prefix}-exec-secrets"
  role   = aws_iam_role.execution.id
  policy = data.aws_iam_policy_document.secrets_read.json
}

# --- タスクロール (アプリ実行時に使用) ---
resource "aws_iam_role" "task" {
  name               = "${var.name_prefix}-ecs-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
  tags               = var.tags
}

data "aws_iam_policy_document" "receipts_access" {
  statement {
    actions   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
    resources = ["${var.receipts_bucket_arn}/*"]
  }
  statement {
    actions   = ["s3:ListBucket"]
    resources = [var.receipts_bucket_arn]
  }
}

resource "aws_iam_role_policy" "task_receipts" {
  name   = "${var.name_prefix}-task-receipts"
  role   = aws_iam_role.task.id
  policy = data.aws_iam_policy_document.receipts_access.json
}

output "execution_role_arn" {
  value = aws_iam_role.execution.arn
}

output "task_role_arn" {
  value = aws_iam_role.task.arn
}
