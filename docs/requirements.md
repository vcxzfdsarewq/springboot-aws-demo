# 経費管理API 要件定義

## 1. プロジェクト概要

Spring Boot で構築する経費管理 REST API。  
Docker コンテナとして AWS (ECS Fargate) にデプロイし、本番運用に必要な DB・ログ・セキュリティ・CI/CD を整備する。

## 2. 機能要件

### 2.1 ユーザー管理

| 機能 | エンドポイント | メソッド | ロール |
|------|-------------|---------|-------|
| ユーザー登録 | `POST /api/auth/signup` | POST | 公開 |
| ユーザー昇格 (ロール変更) | `PUT /api/admin/users/{id}/role` | PUT | ADMIN |
| ログイン (Access+Refresh発行) | `POST /api/auth/login` | POST | 公開 |
| トークン更新 (ローテーション) | `POST /api/auth/refresh` | POST | 公開 (有効なRefresh Token必須) |
| ログアウト (Refresh失効) | `POST /api/auth/logout` | POST | 公開 (有効なRefresh Token必須) |
| プロフィール取得 | `GET /api/users/me` | GET | USER, ADMIN |
| ユーザー一覧 | `GET /api/users` | GET | ADMIN |

> **トークン管理方針** (詳細は [4.1 認証・認可](#41-認証認可))
> - ログイン時に Access Token (15分) と Refresh Token (7日) を発行し、Refresh Token は `refresh_tokens` テーブルにハッシュ化して保存する。
> - `/api/auth/refresh` は **ローテーション方式**: 提示された Refresh Token を失効させ、新しい Access + Refresh のペアを返す。失効済みトークンの再利用を検知したら、そのユーザーの全 Refresh Token を失効させる (盗難検知)。
>   - ただし **grace window (10秒) 内の正規リトライは例外**: 直前のローテーション結果を Redis のリプレイキャッシュから冪等に再返却し、盗難扱いしない (詳細は [3.4](#34-refresh_tokens-テーブル) の冪等性ルール)。
> - `/api/auth/logout` は **有効な Refresh Token の提示**で該当トークンを `revoked_at` でマークする (permitAll。Access Token の有効性に依存しない)。
>
> **ロール割り当て (権限昇格対策)**
> - `signup` は **サーバー側で常に `role=USER` を強制**する。リクエストボディの `role` 指定は無視する (クライアントからの ADMIN 自称を防止)。
> - 初回 ADMIN は **Flyway のシードマイグレーション** (環境変数のメールアドレス + ハッシュ化パスワード) で1名のみブートストラップする。本番では作成後に認証情報をローテーションする。
> - 既存ユーザーの ADMIN 昇格は `PUT /api/admin/users/{id}/role` (ADMIN のみ) で行い、操作はログに監査記録する。

### 2.2 経費管理 (一般ユーザー)

| 機能 | エンドポイント | メソッド | ロール |
|------|-------------|---------|-------|
| 経費登録 | `POST /api/expenses` | POST | USER |
| 経費一覧 (自分の) | `GET /api/expenses` | GET | USER |
| 経費詳細 | `GET /api/expenses/{id}` | GET | USER |
| 経費更新 | `PUT /api/expenses/{id}` | PUT | USER |
| 経費削除 | `DELETE /api/expenses/{id}` | DELETE | USER |
| 経費申請 (承認依頼) | `POST /api/expenses/{id}/submit` | POST | USER |
| 申請取り下げ | `POST /api/expenses/{id}/withdraw` | POST | USER |

> 操作可否はステータスに依存する。詳細は [3.5 経費ステータス遷移](#35-経費ステータス遷移-状態機械) を参照。

### 2.3 領収書管理

| 機能 | エンドポイント | メソッド | ロール |
|------|-------------|---------|-------|
| 領収書アップロード | `POST /api/expenses/{id}/receipts` | POST | USER (所有者) |
| 領収書取得 | `GET /api/expenses/{id}/receipts/{receiptId}` | GET | USER (所有者) / ADMIN |
| 領収書削除 | `DELETE /api/expenses/{id}/receipts/{receiptId}` | DELETE | USER (所有者) |

> **アクセス制御 (IDOR 対策・必須)**
> - USER は **自分が作成した経費 (`expense.user_id == 認証ユーザーID`) に紐づく領収書のみ** 取得・追加・削除できる。
> - すべての領収書操作で `receipt.expense_id == パスの {id}` を検証する (パス改ざん防止)。不一致は 404 を返す (存在を秘匿)。
> - ADMIN は承認業務のため全領収書を取得できるが、追加・削除は不可。
> - 取得は S3 Pre-signed URL (有効期限 5分) を返し、画像バイナリを直接配信しない。

### 2.4 承認管理 (管理者)

| 機能 | エンドポイント | メソッド | ロール |
|------|-------------|---------|-------|
| 申請一覧 | `GET /api/admin/expenses?status=PENDING` | GET | ADMIN |
| 承認 | `POST /api/admin/expenses/{id}/approve` | POST | ADMIN |
| 却下 | `POST /api/admin/expenses/{id}/reject` | POST | ADMIN |
| 月別集計 | `GET /api/admin/reports/monthly?year=2026&month=6&userId=&status=APPROVED` | GET | ADMIN |
| カテゴリ別集計 | `GET /api/admin/reports/by-category?from=2026-01-01&to=2026-06-30&status=APPROVED` | GET | ADMIN |

#### レポート集計仕様

| 項目 | 仕様 |
|------|------|
| 集計対象ステータス | `status` パラメータで指定 (デフォルト `APPROVED`)。`ALL` で全件 |
| 日付基準 | `expense_date` (経費発生日) を基準とする。`created_at` ではない |
| タイムゾーン | サーバー・DB ともに **UTC** で保存。境界判定は `expense_date` (DATE 型) で行うため TZ の影響を受けない |
| ユーザーフィルタ | `userId` (任意)。未指定なら全ユーザー横断 |
| 集計値 | `count` (件数) と `totalAmount` (合計金額、DECIMAL) を返す |
| レスポンス形式 | 月別: `{year, month, count, totalAmount}` / カテゴリ別: `[{category, count, totalAmount}]` (金額降順) |
| 該当0件 | 空配列または `count=0, totalAmount=0` を返す (404 にしない) |

## 3. データモデル

> **日時型ポリシー (全テーブル共通)**
> - 表内で `TIMESTAMP` と表記した列は、PostgreSQL では **`TIMESTAMPTZ` (= `TIMESTAMP WITH TIME ZONE`)** を使用する。値は UTC で保存・比較する。
> - DB セッションは `SET TIME ZONE 'UTC'`、JVM は `-Duser.timezone=UTC`、Hibernate は `hibernate.jdbc.time_zone=UTC` を設定し、保存・取得経路で UTC を強制する。
> - 「日付のみ」を扱う `expense_date` は **`DATE` 型** (タイムゾーンを持たない暦日)。集計の境界判定はこの `DATE` で行うため TZ の影響を受けない ([レポート集計仕様](#レポート集計仕様)と整合)。
> - アプリ層の Java 表現: `TIMESTAMPTZ`→`OffsetDateTime`/`Instant` (UTC)、`DATE`→`LocalDate`。

### 3.1 users テーブル

| カラム | 型 | 説明 |
|--------|------|------|
| id | BIGSERIAL PK | |
| email | VARCHAR(255) UNIQUE | ログインID |
| password_hash | VARCHAR(255) | BCrypt |
| name | VARCHAR(100) | 表示名 |
| role | VARCHAR(20) | USER / ADMIN |
| created_at | TIMESTAMP | |
| updated_at | TIMESTAMP | |

### 3.2 expenses テーブル

| カラム | 型 | 説明 |
|--------|------|------|
| id | BIGSERIAL PK | |
| user_id | BIGINT FK | 申請者 |
| title | VARCHAR(200) | 経費タイトル |
| description | TEXT | 詳細説明 |
| amount | DECIMAL(10,2) | 金額 |
| category | VARCHAR(50) | 交通費 / 宿泊費 / 会議費 等 |
| expense_date | DATE | 経費発生日 |
| status | VARCHAR(20) | DRAFT / PENDING / APPROVED / REJECTED |
| reviewer_id | BIGINT FK NULL | 承認者 |
| reviewed_at | TIMESTAMP NULL | 承認日時 |
| reject_reason | TEXT NULL | 却下理由 |
| version | BIGINT NOT NULL DEFAULT 0 | 楽観ロック用 (JPA `@Version`) |
| created_at | TIMESTAMP | |
| updated_at | TIMESTAMP | |

### 3.3 receipts テーブル

| カラム | 型 | 説明 |
|--------|------|------|
| id | BIGSERIAL PK | |
| expense_id | BIGINT FK | |
| file_name | VARCHAR(255) | 元ファイル名 |
| s3_key | VARCHAR(500) | S3 オブジェクトキー |
| content_type | VARCHAR(100) | MIME タイプ |
| file_size | BIGINT | バイト数 |
| created_at | TIMESTAMP | |

### 3.4 refresh_tokens テーブル

| カラム | 型 | 説明 |
|--------|------|------|
| id | BIGSERIAL PK | |
| user_id | BIGINT FK | 所有ユーザー |
| token_hash | VARCHAR(255) UNIQUE | Refresh Token の SHA-256 ハッシュ (平文は保存しない) |
| expires_at | TIMESTAMP | 発行から7日 |
| revoked_at | TIMESTAMP NULL | 失効日時 (ログアウト/ローテーション/盗難検知で設定) |
| replaced_by | BIGINT FK NULL | ローテーション後の後継トークンID (監査・フォレンジック用のチェーン記録)。盗難判定そのものは Redis リプレイキャッシュで行うため、本カラムは検知ロジックの必須入力ではない |
| created_at | TIMESTAMP | |

- 検証時は受信トークンをハッシュ化して照合。`revoked_at IS NULL AND expires_at > now()` のみ有効。
- 失効済みトークンが提示された場合は再利用攻撃とみなし、当該 `user_id` の全 Refresh Token を失効させる。**ただし grace window (10秒) 内の正規リトライは例外** (下記 冪等性ルール参照)。
- 期限切れレコードはバッチ (日次) で物理削除。

#### ローテーションの同時実行・冪等性ルール

ブラウザの二重送信・ネットワークリトライで、**正規ユーザーが同一の旧トークンを短時間に再提示する**ケースを「盗難」と誤判定せず、かつ**取りこぼし時に同じ後継トークンを安全に再取得できる**ようにする。

> **設計上の制約**: DB (`refresh_tokens`) には Refresh Token を **ハッシュのみ保存**するため、DB だけからは「同じ後継トークン (平文)」を再返却できない。そこで **既に導入済みの Redis ([4.5 Rate Limiting](#rate-limiting)) を冪等リプレイ用ストアとして併用**する。平文トークンは Redis 上に **TTL 付きで揮発的に保持**し、DB には残さない。
>
> **Redis 上の平文トークン保護**: リプレイキャッシュは短命とはいえ Access + Refresh の平文ペアを保持するため、Redis は **TLS + AUTH 必須**、Private Subnet 限定、ECS SG からの 6379 のみ許可とする。`replay:refresh:*` キーは TTL 10秒専用で、AOF/RDB スナップショット等の永続化対象に残さない。アプリケーションログ・構造化ログ・例外ログには、Refresh Token、Access Token、`replay:refresh:*` の値を出力しない。

1. **行ロック**: refresh 処理の起点で対象行を `SELECT ... FOR UPDATE` で取得し、ローテーションをトランザクション内で直列化する (同時 refresh の競合を防止)。
2. **リプレイキャッシュ書き込み**: ローテーション成功時、`replay:refresh:{旧トークンのSHA-256}` キーに **新しい Access + Refresh ペア (平文)** を **TTL 10秒**で Redis に保存する。
3. **猶予期間 (grace window) の冪等再送**: 失効済みの旧トークンが再提示された場合、まず上記リプレイキャッシュを参照する。
   - **ヒット (TTL 10秒以内)** → 盗難扱いにせず、**キャッシュ済みの同じ後継ペアをそのまま返す (200・冪等)**。新たなローテーションは行わない。
   - **ミス (10秒超過 or キー無し)** → 次項の盗難判定へ。
4. **盗難判定**: リプレイキャッシュにヒットしない失効済みトークンの提示のみを再利用攻撃とみなし、`user_id` の全トークンを失効させ **401** を返す。
5. これにより「正規の二重送信/リトライ → 同じ後継ペアを冪等に返す」「真の再利用攻撃 (grace 経過後) → 全失効」を明確に区別する。リプレイキャッシュの TTL 経過後は平文が消えるため、平文の長期保持は発生しない。

> Redis を使わない構成を選ぶ場合の代替: 冪等再送を諦め、grace window 内の旧トークン再提示には **401 (盗難扱いしない)** を返し、二重送信防止はクライアント側の refresh 直列化に委ねる。本プロジェクトは Redis を採用済みのため上記のリプレイ方式を正とする。

### 3.5 経費ステータス遷移 (状態機械)

```
        submit              approve
 DRAFT ────────▶ PENDING ────────────▶ APPROVED
   ▲                │ │
   │ withdraw       │ │ reject
   └────────────────┘ └────────────▶ REJECTED
                                        │
                                        │ (再編集して再申請)
                                        ▼
                                     PENDING
```

| 現ステータス | 許可される操作 | 遷移先 | 実行ロール |
|------------|--------------|--------|-----------|
| DRAFT | 更新 (PUT) / 削除 / 領収書追加・削除 | DRAFT | USER (所有者) |
| DRAFT | 申請 (submit) | PENDING | USER (所有者) |
| PENDING | 取り下げ (withdraw) | DRAFT | USER (所有者) |
| PENDING | 承認 (approve) | APPROVED | ADMIN |
| PENDING | 却下 (reject) | REJECTED | ADMIN |
| REJECTED | 更新 / 領収書追加 / 再申請 (submit) | REJECTED→PENDING | USER (所有者) |
| APPROVED | (変更不可・読み取りのみ) | — | — |

**ルール (サービス層で強制)**
- `PENDING` 中は USER による更新・削除・領収書変更を禁止 (承認者がレビュー中のため)。変更したい場合は先に `withdraw`。
- `APPROVED` は確定状態。更新・削除・領収書変更・再申請をすべて拒否 (409 Conflict)。監査・会計確定のため不変。
- `REJECTED` は再編集・再申請が可能。`reject_reason` は再申請時にクリアし、`reviewer_id`/`reviewed_at` をリセット。
- 不正な遷移要求 (例: DRAFT を approve) は 409 Conflict を返す。
- **同時実行制御**: ステータス遷移は `expenses.version` による **楽観ロック (JPA `@Version`)** を必須とする。2人の管理者が同一 PENDING 案件を同時に approve/reject した場合、後勝ちを防ぎ、版数不一致側は **409 Conflict** で再取得を促す (二重承認・ステータス不整合の防止)。遷移処理はトランザクション内で「現ステータス検証 → 更新」を行う。

## 4. 非機能要件

### 4.1 認証・認可
- Spring Security + JWT (Access Token + Refresh Token)
- Access Token: ステートレス検証 (15分)。Refresh Token: DB 管理 (`refresh_tokens`)・ローテーション方式 (7日)
- ローテーション + 再利用検知でトークン漏洩時に失効可能 (詳細は [3.4](#34-refresh_tokens-テーブル))
- パスワードは BCrypt ハッシュ
- ロールベースアクセス制御 (RBAC): USER / ADMIN
- **ロール変更時のトークン整合性**: Access Token はステートレス検証で role を埋め込むため、ロール変更後も **発行済み Access Token は最大15分 (TTL) まで旧権限が残る**ことを許容する。ただしロール変更時に当該ユーザーの **Refresh Token を全失効**させ、新しい権限のトークンで再ログインを強制する (15分以内に完全に新権限へ収束)。厳密な即時反映が必要になった場合は token version / issued-after 検証を追加する

### 4.2 ファイルストレージ
- AWS S3 に領収書画像を保存
- Pre-signed URL でクライアントに返却
- 最大ファイルサイズ: 10MB
- 対応形式: JPEG, PNG, PDF (Content-Type だけでなく **マジックバイト検証**でなりすましを防ぐ)
- **S3 Block Public Access を全面有効化** (バケット非公開、アクセスは presigned URL のみ)
- **保存時暗号化 (SSE-S3 または SSE-KMS)** を有効化。バケットポリシーで非TLSアクセスを拒否

### 4.3 データベース
- AWS RDS PostgreSQL 16
- Flyway でマイグレーション管理
- ローカル開発は docker-compose で PostgreSQL コンテナ
- **保存時暗号化 (KMS) を有効化**。本番は Multi-AZ + 自動バックアップ。パブリックアクセス無効

### 4.4 ログ・モニタリング
- SLF4J + Logback
- JSON 構造化ログ (CloudWatch Logs 向け)
- リクエスト ID (X-Request-Id) でトレーシング
- ヘルスチェック: Spring Boot Actuator
- **Actuator 露出制御**: ALB ヘルスチェック用に `/actuator/health` のみ公開し、`/actuator/env`・`/heapdump`・`/loggers` 等の機微エンドポイントは無効化または管理ポートに隔離
- ログに **トークン・パスワード・presigned URL・PII を出力しない** (マスキング)

### 4.5 セキュリティ
- HTTPS (ALB で TLS 終端)
- **CORS**: 許可 origin は環境変数 `APP_CORS_ALLOWED_ORIGINS` (カンマ区切り) で注入。既定は空 = クロスオリジン不可。許可メソッド/ヘッダは固定 (GET/POST/PUT/DELETE/OPTIONS、Authorization/Content-Type)
- SQL Injection / XSS 対策 (Spring の標準機能)
- Secrets Manager で DB パスワード・JWT 秘密鍵を管理
- **JWT 秘密鍵は fail-fast**: 既定値を持たず、prod で `JWT_SECRET` 未設定なら起動失敗。開発用の既定は local プロファイルにのみ置く

#### Rate Limiting

二層で制御する。

| 層 | 対象 | キー | 制限 | 目的 |
|----|------|------|------|------|
| AWS WAF (ALB前段) | 全リクエスト | クライアントIP | 例: 2000 req / 5分 | DDoS・ボット・粗いIP制限 |
| アプリ層 (Bucket4j) | 認証・アップロード等のコスト高API | エンドポイント別 | 下表 | ブルートフォース・乱用防止 |

アプリ層の代表的なポリシー (Redis 共有ストアで全タスク共有。実装は Redis Lua 固定ウィンドウ):

| エンドポイント | キー | 制限 | 実装 |
|--------------|------|------|------|
| `POST /api/auth/login` | IP + email | **失敗時のみ** 5回 / 5分。成功でリセット。超過で 429 | AuthService 内で判定 (ボディの email が必要なため) |
| `POST /api/auth/refresh` | IP | 全試行 60回 / 時 | RateLimitFilter |
| `POST /api/expenses/{id}/receipts` | userId | 30回 / 時 (S3コスト保護) | Phase 4 |
| その他一般API | userId | 600回 / 分 | (将来) |

> **login の数え方**: ブルートフォース対策として **認証失敗時のみ** `IP + email` で加算し、成功でカウンタをクリアする。これにより正常ログインの連続では 429 にならず、同一 NAT 配下の別ユーザー(別 email)も巻き込まない。`refresh` は認証前でボディ依存が無いため IP 単位の全試行カウントとする。
>
> **整合性の前提**: 本構成は ECS を **2タスク (Multi-AZ)** で稼働させる ([4.7](#47-パフォーマンス))。インメモリのカウンタはタスクごとに独立し制限が緩むため、**Redis 共有ストアを必須**とする (インメモリ不使用)。実装は bucket4j-redis の配線を避け Redis Lua の固定ウィンドウで同等の原子的共有カウントを行う。
>
> **Redis 障害時のポリシー**: 認証系の Rate Limit は **fail-closed** (Redis 不通なら login は 503、refresh は 503) とし、ブルートフォース防御を無効化させない。一方、Refresh ローテーションのリプレイキャッシュ(冪等再送の最適化)は **fail-open** (ミス扱いでローテーション自体は継続)。両者は構造化レスポンス (503/429) で区別する。

### 4.6 CI/CD
- GitHub Actions
- **ブランチ戦略: `feature/*` → PR → `main`** (シングルブランチ + 環境はタグ/手動承認で出し分け)
  - `feature/*` への push / PR: CI (Lint + Test) のみ
  - `main` へのマージ: Build → ECR Push → **staging へ自動デプロイ**
  - production デプロイ: GitHub Environments の **手動承認 (manual approval)** ゲート経由
- パイプライン: Test → Build → Docker Push (ECR) → Deploy (ECS)

### 4.7 パフォーマンス

目標と測定条件をセットで定義する。

| 項目 | 目標 | 測定前提 |
|------|------|---------|
| レスポンスタイム | p95 < 500ms | 対象: 認証済み JSON API (一覧・詳細・登録)。S3 presigned URL 生成を**含む**が S3 への実アップロード/ダウンロードは除外 |
| 対象データ規模 | — | expenses 約 10万件、1ユーザーあたり最大 1000件、一覧はページネーション 20件/ページ |
| DB状態 | — | RDS warmed (接続プール確立済み、バッファキャッシュ温め済み)。コールドスタート除外 |
| インスタンス | — | RDS: db.t4g.medium (staging) / db.r6g.large (prod)、ECS: 1vCPU/2GB × 2タスク |
| 同時接続 | 100同時ユーザー | k6/Gatling による負荷試験。Ramp-up 30秒、定常 5分 |
| ページネーション | デフォルト20件 / 最大100件 | カーソルではなく offset ベース (MVP) |

> 負荷試験は staging 環境に対して実施し、`expense_date` 等の検索条件にインデックスを付与した状態を前提とする。

## 5. 技術スタック

| レイヤー | 技術 |
|---------|------|
| 言語 | Java 21 |
| フレームワーク | Spring Boot 3.4 |
| 認証 | Spring Security + JWT (jjwt) |
| DB アクセス | Spring Data JPA (Hibernate) |
| マイグレーション | Flyway |
| バリデーション | Jakarta Validation |
| Rate Limit | Bucket4j + Redis (lettuce) |
| API ドキュメント | SpringDoc OpenAPI (Swagger UI) |
| テスト | JUnit 5 + Mockito + Testcontainers |
| ビルド | Maven (JDK25環境のため。`--release 21` で Java 21 バイトコードを生成) |
| コンテナ | Docker + docker-compose |
| クラウド | AWS (ECS Fargate, RDS, ElastiCache for Redis, S3, ALB, WAF, CloudWatch, Secrets Manager) |
| IaC | Terraform |
| CI/CD | GitHub Actions |
