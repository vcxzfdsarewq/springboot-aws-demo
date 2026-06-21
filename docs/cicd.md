# CI/CD (Phase 8: GitHub Actions)

設計: [architecture.md](architecture.md) 5章 / requirements.md 4.6。

## ワークフロー

| ファイル | トリガー | 内容 |
|---------|---------|------|
| [.github/workflows/ci.yml](../.github/workflows/ci.yml) | PR / feature ブランチ push | JDK21 で `mvn verify`(ユニット + **Testcontainers 統合テスト**)、`terraform fmt -check` + `validate` |
| [.github/workflows/deploy.yml](../.github/workflows/deploy.yml) | `main` への push | test → イメージ build/push(ECR) → **staging 自動** → **production 手動承認** |
| [.github/actions/ecs-deploy](../.github/actions/ecs-deploy/action.yml) | (composite) | タスク定義のイメージだけ差し替えて新リビジョン登録 → サービス更新 → 安定待ち |

ブランチ戦略: `feature/* → PR → main`。main マージで staging に自動デプロイ、production は GitHub Environment の承認ゲート通過後に **同一イメージ(同一 git SHA タグ)を昇格**。

## AWS 認証は OIDC(長期キー不要)・ロールは用途/環境別に分離

1. CI 用の OIDC プロバイダ + 3つのロールを作成:

   ```bash
   cd terraform/cicd
   terraform init
   terraform apply -var="github_org=YOUR_ORG" -var="github_repo=springboot-aws-demo"
   # 出力: build_role_arn / staging_deploy_role_arn / production_deploy_role_arn
   ```

   各ロールの assume 条件と権限(最小権限):
   | ロール | assume できる主体 (OIDC sub) | 権限 |
   |--------|------------------------------|------|
   | build | `main` ブランチのジョブのみ | ECR push/pull(expense-api のみ) |
   | deploy-staging | `environment:staging` のジョブのみ | staging の ECS cluster/service 更新 + staging タスクロール PassRole |
   | deploy-production | `environment:production` のジョブのみ | prod の ECS cluster/service 更新 + prod タスクロール PassRole |

2. GitHub リポジトリの設定:
   - **Settings → Secrets and variables → Actions → Variables(repo レベル)**
     - `AWS_BUILD_ROLE_ARN` = `build_role_arn`
     - `AWS_REGION` = `ap-northeast-1`
   - **Settings → Environments** で `staging` と `production` を作成し、各 Environment の Variables に:
     - `AWS_ROLE_ARN`(staging: `staging_deploy_role_arn` / production: `production_deploy_role_arn`)
     - `ECS_CLUSTER`(`expense-staging-cluster` / `expense-prod-cluster`)
     - `ECS_SERVICE`(`expense-staging-app` / `expense-prod-app`)
     - `ECS_TASK_FAMILY`(`expense-staging-app` / `expense-prod-app`)
   - `production` Environment に **Required reviewers** を設定 → これが手動承認ゲートになる

   > 環境別ロール + OIDC sub を `environment:<name>` に固定することで、staging ジョブから production の ECS を更新する余地を IAM レベルでも塞ぐ。

## デプロイの流れ

```
PR ──ci.yml──▶ test + tf validate
main merge ──deploy.yml──▶ test ─▶ build/push(ECR) ─▶ staging自動 ─▶ [承認] ─▶ production
```

- イメージタグは `git-<SHA12>`(ECR は IMMUTABLE タグ)
- ECS サービスは Terraform で `lifecycle.ignore_changes = [task_definition]` のため、CI のデプロイと競合しない
- 初回は Terraform で ECS/ECR を作成済みであること(Phase 7)。タスク定義が存在しないと `ecs-deploy` が失敗する

## 前提

- Phase 7 のインフラ(ECR/ECS/...)が apply 済み
- `terraform/cicd` を apply して OIDC ロールを作成済み
- GitHub Environments と Variables を設定済み
