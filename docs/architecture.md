# 経費管理API アーキテクチャ設計

## 1. システム構成図

凡例: 【Public Subnet】【Private Subnet】はVPC内のサブネット。
S3 / Secrets Manager / CloudWatch / ECR は **VPC外のリージョナルAWSサービス** で、
ECS タスクからは **VPC Endpoint** 経由でプライベートに到達する (サブネットには配置されない)。

```
                          ┌──────────┐
  Internet ──── HTTPS ───▶│ Route53  │
                          └────┬─────┘
                               │
┌──────────────────────────────┼─────────────────────────────────────────┐
│  AWS Cloud / VPC 10.0.0.0/16  │                                         │
│                               ▼                                         │
│  ╔══ Public Subnet (AZ-a / AZ-c) ══════════════════════════════════╗   │
│  ║   ┌──────────────────┐        ┌──────────────┐                  ║   │
│  ║   │   ALB (HTTPS)     │        │ NAT Gateway   │                  ║   │
│  ║   │  TLS終端/ヘルス   │        │ (送信用)      │                  ║   │
│  ║   └─────────┬────────┘        └──────┬───────┘                  ║   │
│  ╚═════════════╪════════════════════════╪══════════════════════════╝   │
│                │ :8080                   │                              │
│  ╔══ Private Subnet (AZ-a / AZ-c) ═══════╪══════════════════════════╗   │
│  ║   ┌─────────▼────────────────────┐    │                          ║   │
│  ║   │   ECS Fargate Cluster         │    │                          ║   │
│  ║   │  Task1 / Task2 / ... (Spring) │    │                          ║   │
│  ║   └───┬───────────┬──────────────┬─────┘    │                     ║   │
│  ║       │ :5432     │ :6379        │          │                     ║   │
│  ║  ┌────▼─────┐ ┌───▼──────────┐ ┌─▼────────────────┐│              ║   │
│  ║  │ RDS      │ │ ElastiCache   │ │ VPC Endpoints     ││              ║   │
│  ║  │ Postgres │ │ Redis         │ │ (Interface/GW)    ││              ║   │
│  ║  │ Multi-AZ │ │ (RateLimit共有)│ └────┬──────────────┘│             ║   │
│  ║  └──────────┘ └───────────────┘      │               │             ║   │
│  ╚════════════════════════╪═══════════════╪══════════════════════════╝   │
│                           │ (VPC Endpoint) │ (NAT: 外部HTTPS)           │
└───────────────────────────┼───────────────┼─────────────────────────────┘
                            ▼               ▼
        ┌──────────── リージョナルAWSサービス (VPC外) ─────────────┐
        │  S3(領収書)  Secrets Manager  CloudWatch Logs  ECR     │
        └──────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────┐
│              GitHub Actions CI/CD            │
│  Test → Build → Push ECR → Deploy ECS       │
└─────────────────────────────────────────────┘
```

## 2. VPC ネットワーク設計

```
VPC: 10.0.0.0/16

├── Public Subnet (AZ-a):  10.0.1.0/24   ← ALB, NAT Gateway
├── Public Subnet (AZ-c):  10.0.2.0/24   ← ALB (冗長化)
├── Private Subnet (AZ-a): 10.0.11.0/24  ← ECS Tasks, RDS, ElastiCache
└── Private Subnet (AZ-c): 10.0.12.0/24  ← ECS Tasks, RDS, ElastiCache (冗長化)
```

- ALB は Public Subnet に配置 (インターネットからアクセス可能)
- ECS タスク・RDS・ElastiCache (Redis) は Private Subnet に配置 (直接アクセス不可)

### 2.1 AWSマネージドサービスへの到達経路 (VPC Endpoint vs NAT)

S3 / Secrets Manager / CloudWatch / ECR は VPC 内のリソースではなくリージョナルサービス。ECS タスク (Private Subnet) からの到達経路を以下に固定する。

| サービス | エンドポイント種別 | 理由 |
|---------|------------------|------|
| S3 | **Gateway VPC Endpoint** | 無料・NAT通信量削減。領収書アップロードの帯域がNAT課金に乗らない |
| ECR (api + dkr) | **Interface VPC Endpoint** | イメージpullをプライベート化。NAT経由を避け安定・低コスト |
| Secrets Manager | **Interface VPC Endpoint** | 認証情報取得をインターネットに出さない |
| CloudWatch Logs | **Interface VPC Endpoint** | ログ送信をプライベート化 |
| 上記以外の外部HTTPS | **NAT Gateway** | OS更新・外部API等のフォールバック経路 |

> 方針: コア依存 (S3/ECR/Secrets/Logs) はすべて VPC Endpoint で到達し、NAT Gateway は補助経路とする。これによりインターネット露出とNATデータ処理料金を最小化し、Terraform でも `aws_vpc_endpoint` として明示管理する。Interface Endpoint 用に専用 Security Group (ECS SG からの 443 のみ許可) を作成する。

## 3. アプリケーション層アーキテクチャ

```
┌──────────────────────────────────────────────────────────┐
│                    Spring Boot Application                │
│                                                          │
│  ┌─────────────┐   ┌─────────────┐   ┌──────────────┐  │
│  │  Controller  │──▶│   Service    │──▶│  Repository   │  │
│  │  (REST API)  │   │ (ビジネス   │   │  (JPA/DB)     │  │
│  │              │   │  ロジック)  │   │               │  │
│  └──────┬───────┘   └──────┬───────┘   └──────────────┘  │
│         │                  │                              │
│  ┌──────▼───────┐   ┌──────▼───────┐                     │
│  │   DTO        │   │  S3 Client   │                     │
│  │ (Req / Res)  │   │ (領収書)     │                     │
│  └──────────────┘   └──────────────┘                     │
│                                                          │
│  ┌──────────────────────────────────────────────────┐    │
│  │              Security Filter Chain                │    │
│  │  JWT Filter → Auth Manager → SecurityContext      │    │
│  └──────────────────────────────────────────────────┘    │
│                                                          │
│  ┌──────────────────────────────────────────────────┐    │
│  │              Cross-cutting Concerns               │    │
│  │  Logging │ Exception Handler │ Request ID Filter  │    │
│  └──────────────────────────────────────────────────┘    │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

### パッケージ構成

```
src/main/java/com/example/expense/
├── ExpenseApplication.java
├── config/
│   ├── SecurityConfig.java
│   ├── S3Config.java
│   └── WebConfig.java
├── controller/
│   ├── AuthController.java
│   ├── ExpenseController.java
│   ├── ReceiptController.java
│   └── AdminController.java
├── service/
│   ├── AuthService.java
│   ├── RefreshTokenService.java      ← 発行/検証/ローテーション/失効/盗難検知
│   ├── ExpenseService.java           ← ステータス状態機械を強制
│   ├── ReceiptService.java           ← 所有者チェック(IDOR対策)
│   ├── ReportService.java
│   └── S3StorageService.java
├── repository/
│   ├── UserRepository.java
│   ├── ExpenseRepository.java
│   ├── ReceiptRepository.java
│   └── RefreshTokenRepository.java
├── entity/
│   ├── User.java
│   ├── Expense.java
│   ├── ExpenseStatus.java            ← enum: DRAFT/PENDING/APPROVED/REJECTED
│   ├── Receipt.java
│   └── RefreshToken.java
├── dto/
│   ├── request/
│   │   ├── SignupRequest.java
│   │   ├── LoginRequest.java
│   │   ├── RefreshTokenRequest.java
│   │   ├── ExpenseRequest.java
│   │   └── RejectRequest.java
│   └── response/
│       ├── AuthResponse.java
│       ├── ExpenseResponse.java
│       ├── ReceiptResponse.java
│       └── MonthlyReportResponse.java
├── security/
│   ├── JwtTokenProvider.java
│   ├── JwtAuthenticationFilter.java
│   └── CustomUserDetailsService.java
├── ratelimit/
│   └── RateLimitFilter.java          ← Bucket4j (login/refresh/upload)
├── exception/
│   ├── GlobalExceptionHandler.java
│   ├── ResourceNotFoundException.java
│   ├── InvalidStateTransitionException.java  ← 409: 不正なステータス遷移
│   └── UnauthorizedException.java
└── filter/
    └── RequestIdFilter.java
```

## 4. 環境構成

| 環境 | 用途 | DB / キャッシュ | 備考 |
|------|------|----|------|
| local | 開発 | docker-compose: PostgreSQL **+ Redis** | H2 ではなく本番同等。Redis は Rate Limit + リフレッシュ冪等で必須 |
| test | テスト | Testcontainers: PostgreSQL **+ Redis** | 認証・Rate Limit の統合テストに Redis コンテナが必要 |
| staging | 検証 | RDS (小) + ElastiCache (小) | 本番と同じ構成 |
| production | 本番 | RDS (Multi-AZ) + ElastiCache (Multi-AZ) | |

### application.yml の構成

```
application.yml          ← 共通設定
application-local.yml    ← ローカル開発用
application-staging.yml  ← ステージング
application-prod.yml     ← 本番
```

## 5. CI/CD パイプライン

```
GitHub (Push / PR)
    │
    ▼
┌──────────────────────────────────────────────┐
│              GitHub Actions                   │
│                                              │
│  ┌──────┐   ┌──────┐   ┌──────┐   ┌──────┐ │
│  │ Lint  │──▶│ Test  │──▶│Build │──▶│ Push │ │
│  │      │   │      │   │Docker│   │ ECR  │ │
│  └──────┘   └──────┘   └──────┘   └──────┘ │
│                                       │      │
│                                       ▼      │
│                               ┌──────────┐   │
│                               │ Deploy   │   │
│                               │ ECS      │   │
│                               └──────────┘   │
└──────────────────────────────────────────────┘
```

### ブランチ戦略 (feature → main シングルブランチ)

```
feature/* ──PR──▶ main ──(auto)──▶ staging ──(manual approval)──▶ production
   │              │                  │                              │
 CI Test       Build +            ECS自動デプロイ              GitHub Environments
 のみ          ECR Push           (staging)                   手動承認後にデプロイ
```

- `feature/*` の push / PR: CI (Lint + Test) のみ実行。
- `main` へのマージ: Docker ビルド → ECR push → **staging へ自動デプロイ**。
- production: GitHub Environments の `production` に **required reviewers** を設定し、手動承認ゲート通過後に同一イメージ (同一タグ) を昇格デプロイする。
- 環境差分はブランチではなく **デプロイワークフローの環境変数 / タスク定義** で吸収する。

## 6. セキュリティ設計

### 6.1 認証フロー

```
Client                     API                      DB (users / refresh_tokens)
  │                          │                        │
  │  POST /api/auth/login    │                        │
  │  {email, password}       │                        │
  │─────────────────────────▶│  SELECT user           │
  │                          │───────────────────────▶│
  │                          │  BCrypt verify         │
  │                          │  INSERT refresh_token   │
  │                          │  (hash, expires_at)     │
  │                          │───────────────────────▶│
  │  {accessToken(15分),     │                        │
  │   refreshToken(7日)}     │                        │
  │◀─────────────────────────│                        │
  │                          │                        │
  │  GET /api/expenses       │                        │
  │  Authorization: Bearer   │  JWT署名+期限+権限検証 │
  │  <accessToken>           │  (DBアクセスなし)       │
  │─────────────────────────▶│                        │
  │  200 OK                  │                        │
  │◀─────────────────────────│                        │
  │                          │                        │
  │ (accessToken期限切れ)    │                        │
  │  POST /api/auth/refresh  │  hash照合 (FOR UPDATE)  │
  │  {refreshToken}          │───────────────────────▶│
  │                          │  有効?(revoked/expires) │
  │                          │                        │       Redis
  │                          │  ┌── 失効済み再提示 ──┐│   (replay cache)
  │                          │  │ replay:{hash} 確認 │┼──────▶ │
  │                          │  │  HIT(10秒以内):     ││◀────── │
  │                          │  │   同じ後継を再返却  ││  冪等200(盗難扱いせず)
  │                          │  │  MISS(grace経過):   ││
  │                          │  │   全token失効/401   ││
  │                          │  └────────────────────┘│
  │                          │  [正常] 旧tokenrevoke + │
  │                          │  新token発行(rotation) +│
  │                          │  replay:{hash} TTL10秒  │──────▶ Redis
  │                          │───────────────────────▶│ (DB)
  │  {new accessToken,       │                        │
  │   new refreshToken}      │                        │
  │◀─────────────────────────│                        │
  │                          │                        │
  │  POST /api/auth/logout   │  Refresh Token提示で失効│
  │  {refreshToken}          │  (permitAll/AccessToken │
  │  (Access期限切れでも可)  │   不要) revoked_at=now() │
  │─────────────────────────▶│───────────────────────▶│
  │  204 No Content          │                        │
  │◀─────────────────────────│                        │
```

### 6.2 JWT / トークン設定

| 項目 | 値 |
|------|-----|
| Access Token 有効期限 | 15分 (ステートレス検証・DBアクセスなし) |
| Refresh Token 有効期限 | 7日 (DB管理・ローテーション) |
| Refresh Token 保存 | `refresh_tokens` に SHA-256 ハッシュで保存 (平文非保存) |
| ローテーション | refresh毎に旧tokenを失効し新ペア発行。後継ペアを Redis `replay:{hash}` に TTL10秒保持 |
| 冪等リトライ | grace window 10秒内の旧token再提示は Redis から同じ後継ペアを冪等再返却 (盗難扱いしない) |
| 盗難検知 | grace 経過後の失効済みtoken再提示でユーザー全token失効 (401) |
| ログアウト | **permitAll** (Access Token不要)。有効な Refresh Token 提示で `revoked_at` マーク。期限切れ Access でも失効可能 |
| アルゴリズム | HS256 |
| 秘密鍵管理 | AWS Secrets Manager |

### 6.3 セキュリティグループ

| リソース | インバウンド | ソース |
|---------|------------|--------|
| ALB | 443 (HTTPS) | 0.0.0.0/0 |
| ECS | 8080 | ALB SG |
| RDS | 5432 | ECS SG |
| ElastiCache (Redis) | 6379 | ECS SG |
| VPC Endpoints (Interface) | 443 | ECS SG |

## 7. ディレクトリ構成 (プロジェクト全体)

```
springboot-aws-demo/
├── docs/
│   ├── requirements.md
│   └── architecture.md
├── src/                        ← Spring Boot アプリ
│   ├── main/
│   │   ├── java/
│   │   └── resources/
│   └── test/
├── docker/
│   ├── Dockerfile
│   └── docker-compose.yml      ← ローカル開発用 (PostgreSQL + Redis)
├── terraform/
│   ├── environments/
│   │   ├── staging/
│   │   └── production/
│   └── modules/
│       ├── vpc/                ← サブネット + VPC Endpoints (S3/ECR/Secrets/Logs)
│       ├── ecs/
│       ├── rds/
│       ├── elasticache/        ← Redis (Rate Limit 共有ストア)
│       ├── s3/
│       ├── alb/
│       └── waf/               ← WAF (IPレート制限・マネージドルール)
├── .github/
│   └── workflows/
│       ├── ci.yml              ← feature/PR 時のテスト
│       └── deploy.yml          ← main マージ→staging自動 / production手動承認
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

## 8. 実装フェーズ計画

### Phase 1: Spring Boot API 基盤 (Week 1)
- [ ] Spring Boot プロジェクト初期化 (Spring Initializr)
- [ ] エンティティ + リポジトリ (日時は TIMESTAMPTZ / UTC 強制設定: JVM・Hibernate・DBセッション)
- [ ] 経費 CRUD API
- [ ] **経費ステータス状態機械** (ExpenseStatus enum + サービス層で遷移強制、不正遷移は 409)
- [ ] **楽観ロック** (`@Version`) で承認/却下の同時実行レースを防止 (版数不一致は 409)
- [ ] バリデーション + 例外ハンドリング (InvalidStateTransitionException / OptimisticLock 含む)
- [ ] ユニットテスト + 統合テスト (Testcontainers: PostgreSQL + Redis)

### Phase 2: Docker 化 (Week 1)
- [ ] Dockerfile (マルチステージビルド)
- [ ] docker-compose.yml (PostgreSQL **+ Redis** + アプリ)
- [ ] ローカル動作確認

### Phase 3: 認証・認可 (Week 2)
- [ ] Spring Security 設定 (`/api/auth/**` を permitAll、それ以外を認証必須)
- [ ] JWT (Access) 発行・ステートレス検証
- [ ] ユーザー登録・ログイン API (**signup は role=USER 強制**、初回 ADMIN は Flyway シード)
- [ ] ユーザー昇格 API `PUT /api/admin/users/{id}/role` (ADMIN のみ・監査ログ)
- [ ] **Refresh Token ローテーション** (`refresh_tokens` テーブル、SHA-256 ハッシュ保存)
  - [ ] `/api/auth/refresh` (SELECT...FOR UPDATE で直列化)
  - [ ] **Redis リプレイキャッシュ** (`replay:{hash}` TTL10秒) で grace window 内の冪等再送
  - [ ] `/api/auth/logout` (**permitAll** + 有効な Refresh Token 提示で失効、Access 期限切れでも可)
  - [ ] grace 経過後の失効済み再利用検知 → ユーザー全トークン失効 (盗難検知)
  - [ ] 期限切れトークンの日次クリーンアップバッチ
- [ ] **Rate Limiting** (Bucket4j + Redis 共有ストア: login/refresh/upload ポリシー)
- [ ] ロールベースアクセス制御 (RBAC)

### Phase 4: ファイルアップロード (Week 2)
- [ ] S3 クライアント設定 (バケット: Block Public Access + SSE 暗号化 + 非TLS拒否)
- [ ] 領収書アップロード API (**マジックバイト検証** + サイズ/形式チェック)
- [ ] **所有者チェック (IDOR 対策)**: expense.user_id 照合 + receipt.expense_id == path id 検証
- [ ] Pre-signed URL 生成 (有効期限 5分)

### Phase 5: 管理者機能 (Week 3)
- [ ] 承認・却下 API (ステータス状態機械と整合)
- [ ] 月別集計・カテゴリ別集計 (集計対象ステータス/expense_date 基準/UTC)
- [ ] レポート API

### Phase 6: ログ・モニタリング & セキュリティ統合 (Week 3)
- [ ] 構造化ログ設定 (JSON) + **機微情報マスキング** (トークン/パスワード/presigned URL/PII)
- [ ] リクエスト ID フィルター
- [ ] Actuator ヘルスチェック (**`/actuator/health` のみ公開、機微エンドポイント無効化**)
- [ ] **Secrets Manager 連携** (DB パスワード・JWT 秘密鍵・Redis 認証を起動時にロード)
- [ ] CORS / セキュリティヘッダ設定

### Phase 7: Terraform + AWS (Week 4)
- [ ] VPC / サブネット
- [ ] **VPC Endpoints** (S3=Gateway、ECR/Secrets/Logs=Interface) + Endpoint 用 SG
- [ ] ECS Fargate クラスター (2タスク Multi-AZ)
- [ ] NAT Gateway (**本番は AZ ごとに配置**して SPOF・クロスAZ課金を回避。MVP/staging は単一可)
- [ ] RDS PostgreSQL (Multi-AZ、**保存時暗号化 KMS**、自動バックアップ、パブリックアクセス無効)
- [ ] **ElastiCache for Redis** (Rate Limit 共有ストア)
  - [ ] TLS + AUTH 必須、Private Subnet 配置、ECS SG からの 6379 のみ許可
  - [ ] Refresh Token リプレイキャッシュ (`replay:refresh:*`) が永続化・スナップショット・ログに残らない設定
- [ ] ALB + Target Group
- [ ] **WAF** (レートベースルール + マネージドルール、ALB にアタッチ)
- [ ] S3 バケット (**Block Public Access 全面有効・SSE 暗号化・非TLS拒否ポリシー**)
- [ ] ECR リポジトリ
- [ ] **Secrets Manager** (シークレット定義 + IAM ポリシー)
- [ ] IAM ロール (タスクロール: S3/Secrets/Logs アクセス) + セキュリティグループ

### Phase 8: CI/CD (Week 4)
- [ ] GitHub Actions CI (feature/PR: Lint + Test)
- [ ] Docker イメージ → ECR プッシュ (main マージ時)
- [ ] staging 自動デプロイ + production 手動承認ゲート (GitHub Environments)
- [ ] ECS サービス更新 (ローリングデプロイ)
