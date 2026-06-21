# Terraform (Phase 7: AWS インフラ)

経費管理API の AWS インフラを IaC で構築する。設計: [docs/architecture.md](../docs/architecture.md)。

## 構成

```
terraform/
├── root/                 ← 全モジュールを配線する共通構成
├── modules/
│   ├── network/          ← VPC, サブネット, NAT, ルート, S3 Gateway エンドポイント
│   ├── endpoints/        ← Interface VPC エンドポイント (ECR/Secrets/Logs)
│   ├── security/         ← 全セキュリティグループ (ALB/ECS/RDS/Redis/VPCE)
│   ├── s3/               ← 領収書バケット (Block Public Access + SSE + 非TLS拒否)
│   ├── ecr/              ← Docker リポジトリ
│   ├── secrets/          ← Secrets Manager (JWT/DB/Redis、ランダム生成)
│   ├── iam/              ← ECS 実行ロール + タスクロール
│   ├── rds/              ← PostgreSQL (Multi-AZ/KMS暗号化/非公開)
│   ├── elasticache/      ← Redis (TLS + AUTH)
│   ├── alb/              ← ALB + Target Group + リスナー
│   ├── ecs/              ← Fargate クラスター/タスク定義/サービス/オートスケール
│   └── waf/              ← WAF (レートベース + マネージドルール)
└── environments/
    ├── staging/          ← コスト優先 (NAT 1つ・single-AZ・小サイズ)
    └── production/       ← 可用性優先 (NAT AZごと・Multi-AZ・HTTPS必須)
```

## 前提

- Terraform >= 1.6、AWS プロバイダ ~> 5.60
- `container_image` は **IMMUTABLE タグ**(git SHA / タイムスタンプ)。`:latest` は使えない
- ECR イメージは、初回は ECR 作成後に手動 push するか、Phase 8 の CI/CD で push

## ⚠️ apply の前に: リモート state を必ず有効化する

`modules/secrets` の `random_password`(DB / JWT / Redis の秘密値)は **Terraform state に平文で保存される**。
ローカル state ファイルに秘密を残さないため、**暗号化された S3 backend を必須**とする。

```bash
# 1) state 用バケット + ロックテーブルを一度だけ作成
cd terraform/bootstrap
terraform init
terraform apply -var="state_bucket_name=expense-tfstate-<アカウントID>"

# 2) 出力されたバケット名を各 env の backend "s3" に設定して有効化
#    (environments/staging/main.tf と production/main.tf のコメントを外す)
```

backend を有効化せずに apply すると、`terraform.tfstate`(秘密入り)がローカルに生成される。
`.gitignore` で除外済みだが、**コミット/共有は厳禁**。

## 使い方 (staging)

```bash
cd terraform/environments/staging
cp terraform.tfvars.example terraform.tfvars   # 値を編集
terraform init
terraform plan
terraform apply
```

ローカル検証 (AWS 認証情報なしで構文チェック):

```bash
terraform init -backend=false
terraform validate
terraform fmt -recursive
```

## 環境差分

| 項目 | staging | production |
|------|---------|-----------|
| NAT Gateway | 1つ (共有) | AZ ごと |
| RDS | db.t4g.medium / single-AZ | db.r6g.large / Multi-AZ |
| Redis | cache.t4g.small / single | cache.r6g.large / Multi-AZ |
| ECS タスク | 1 | 2 (CPU 追従で最大6) |
| 削除保護 | 無効 | 有効 |
| ALB | HTTP (検証用) | HTTPS (ACM 証明書必須) |

## アプリへの接続情報の流れ

ECS タスク定義が以下を注入する:
- 環境変数: `DB_URL`, `REDIS_HOST`, `REDIS_PORT`, `REDIS_SSL=true`, `S3_BUCKET`, `AWS_REGION`, `SPRING_PROFILES_ACTIVE`
- Secrets Manager (secrets): `JWT_SECRET`, `DB_PASSWORD`, `REDIS_PASSWORD`

> 注: Flyway マイグレーションはアプリ起動時に実行される。初回起動時に `users`/`expenses`/`refresh_tokens`/`receipts` が作成される。本番の初期 ADMIN は別手順で投入する (db/seed は local/test のみ)。
