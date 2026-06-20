# 経費管理API (expense-api)

Spring Boot で構築する経費管理 REST API。Docker 化し AWS (ECS Fargate) へデプロイする学習プロジェクト。

設計: [docs/requirements.md](docs/requirements.md) / [docs/architecture.md](docs/architecture.md)

## 進捗

- [x] **Phase 0**: 要件定義・アーキテクチャ設計
- [x] **Phase 1**: Spring Boot 基盤 (経費 CRUD + ステータス状態機械 + 楽観ロック)
- [x] **Phase 2**: Docker 化 (マルチステージ Dockerfile + docker-compose)
- [x] **Phase 3**: 認証・認可 (Spring Security + JWT + Refresh Token ローテーション + Rate Limit)
- [ ] Phase 4〜8: S3 / 管理者機能 / ログ / Terraform / CI/CD

## 技術スタック (Phase 1)

Java 21 (JDK25環境で `--release 21`) / Spring Boot 3.4 / Spring Data JPA / Flyway / PostgreSQL / Maven / JUnit5 + Mockito + Testcontainers

## ビルド & テスト

```bash
mvn clean test      # ユニット + (Docker があれば) 統合テスト
mvn clean package   # jar 生成
```

> 統合テスト (`ExpenseApiIntegrationTest`) は PostgreSQL Testcontainer を使用し、
> **Docker が無い環境では自動スキップ**されます (`@Testcontainers(disabledWithoutDocker = true)`)。

## ローカル実行

### 方法A: docker compose (推奨)

PostgreSQL + Redis + アプリをまとめて起動します。Docker さえあれば他に準備は不要です。

```bash
docker compose -f docker/docker-compose.yml up --build
# 停止: Ctrl-C / 破棄: docker compose -f docker/docker-compose.yml down -v
```

- アプリ: http://localhost:8080 (ヘルス: `/actuator/health`)
- 起動時に Flyway が `users` / `expenses` を作成し、開発用ユーザーをシード
  (`admin@example.com` = ADMIN(id=1) / `user@example.com` = USER(id=2))

### 方法B: ローカル JVM で起動

別途 PostgreSQL を用意し、環境変数で接続情報を渡します。

```bash
export DB_URL=jdbc:postgresql://localhost:5432/expense
export DB_USERNAME=expense DB_PASSWORD=expense
mvn spring-boot:run
```

## API (Phase 1)

| 機能 | メソッド | パス | 認可 |
|------|---------|------|------|
| サインアップ | POST | `/api/auth/signup` | 公開 |
| ログイン | POST | `/api/auth/login` | 公開 |
| トークン更新 | POST | `/api/auth/refresh` | 公開 (Refresh Token) |
| ログアウト | POST | `/api/auth/logout` | 公開 (Refresh Token) |
| プロフィール | GET | `/api/users/me` | USER/ADMIN |
| ユーザー一覧 | GET | `/api/users` | ADMIN |
| ロール変更 | PUT | `/api/admin/users/{id}/role` | ADMIN |
| 経費 CRUD | - | `/api/expenses/**` | USER (所有者) |
| 申請/取り下げ | POST | `/api/expenses/{id}/submit` `.../withdraw` | USER |

- 認証: **JWT (Access 15分) + Refresh Token (7日, ローテーション)**。`Authorization: Bearer <accessToken>`
- ステータス: `DRAFT → PENDING → APPROVED / REJECTED` (不正遷移は 409)、`@Version` 楽観ロック
- Rate Limit: login 5回/5分・refresh 60回/時 (Redis 共有ストア、超過で 429)
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- 開発用シードユーザー: `admin@example.com` / `user@example.com` (パスワード `Password123!`)

### 例

```bash
# ログインしてアクセストークン取得
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"user@example.com","password":"Password123!"}' | jq -r .accessToken)

# トークンを付けて経費登録
curl -X POST http://localhost:8080/api/expenses \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
  -d '{"title":"タクシー代","amount":1200.00,"category":"交通費","expenseDate":"2026-06-20"}'
```
