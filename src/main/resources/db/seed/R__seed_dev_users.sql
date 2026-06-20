-- 開発/テスト専用シード (Repeatable migration)。
-- このフォルダ (classpath:db/seed) は local / test プロファイルでのみ Flyway に読み込まれる。
-- staging / production では db/migration のみを適用するため、ここは実行されない。
--
-- 初回 ADMIN のブートストラップ + 動作確認用 USER。
-- password_hash は BCrypt のプレースホルダ ("Password123!" 相当の例)。
-- 本番の初期管理者は別手順 (Secrets で渡す専用ジョブ等) で作成し、ここは使わない。
-- ON CONFLICT で冪等にしているため繰り返し適用されても安全。

INSERT INTO users (email, password_hash, name, role) VALUES
    ('admin@example.com', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'Initial Admin', 'ADMIN'),
    ('user@example.com',  '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'Demo User',     'USER')
ON CONFLICT (email) DO NOTHING;
