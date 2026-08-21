-- 스키마만 잡아둔다. 테이블은 각 서비스 JPA(ddl-auto=update)가 만들게 두고,
-- 확정되면 여기로 옮겨 적을 예정 (TODO.md 5단계).
CREATE DATABASE IF NOT EXISTS delivery
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

GRANT ALL PRIVILEGES ON delivery.* TO 'dev_user'@'%';
FLUSH PRIVILEGES;
