# 経費管理API (expense-api)

Spring Boot で構築する経費管理 REST API。Docker 化し AWS (ECS Fargate) へデプロイする学習プロジェクト。

設計: [docs/requirements.md](docs/requirements.md) / [docs/architecture.md](docs/architecture.md)

## 進捗

- [x] **Phase 0**: 要件定義・アーキテクチャ設計
- [x] **Phase 1**: Spring Boot 基盤 (経費 CRUD + ステータス状態機械 + 楽観ロック)
- [x] **Phase 2**: Docker 化 (マルチステージ Dockerfile + docker-compose)
- [ ] Phase 3: 認証・認可 (JWT / Refresh Token)
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

| 機能 | メソッド | パス |
|------|---------|------|
| 経費登録 | POST | `/api/expenses` |
| 経費一覧 (自分の) | GET | `/api/expenses?page=0&size=20` |
| 経費詳細 | GET | `/api/expenses/{id}` |
| 経費更新 | PUT | `/api/expenses/{id}` |
| 経費削除 | DELETE | `/api/expenses/{id}` |
| 申請 | POST | `/api/expenses/{id}/submit` |
| 取り下げ | POST | `/api/expenses/{id}/withdraw` |

- ステータス: `DRAFT → PENDING → APPROVED / REJECTED` (不正遷移は 409)
- 承認/却下の同時実行は `@Version` 楽観ロックで防止 (衝突は 409)
- Swagger UI: `http://localhost:8080/swagger-ui.html`

> **Phase 1 の暫定認証**: Spring Security は Phase 3 で導入します。それまで操作ユーザーは
> リクエストヘッダ `X-User-Id` で指定します (例: `-H "X-User-Id: 2"`)。Phase 3 で SecurityContext に置き換えます。

### 例

```bash
curl -X POST http://localhost:8080/api/expenses \
  -H "Content-Type: application/json" -H "X-User-Id: 2" \
  -d '{"title":"タクシー代","amount":1200.00,"category":"交通費","expenseDate":"2026-06-20"}'
```
