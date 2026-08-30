-- 스키마만 잡아둔다. 테이블은 각 서비스 JPA(ddl-auto=update)가 만들게 두고,
-- 확정되면 여기로 옮겨 적을 예정 (TODO.md 5단계).
CREATE DATABASE IF NOT EXISTS delivery
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

GRANT ALL PRIVILEGES ON delivery.* TO 'dev_user'@'%';
FLUSH PRIVILEGES;

-- Debezium 이 binlog 를 읽는 데 쓰는 계정.
-- REPLICATION SLAVE 는 binlog 스트림을 받는 권한이고, REPLICATION CLIENT 는
-- "지금 binlog 어디까지 왔나" 를 물어보는 권한이다. RELOAD 와 LOCK TABLES 는
-- 커넥터가 처음 붙어 테이블 모양을 읽는 순간에만 잠깐 쓴다.
--
-- 이 파일은 데이터 디렉터리가 빈 상태에서만 실행된다. 이미 쓰던 볼륨에 붙일 때는
-- infra/register-debezium.sh 가 같은 내용을 다시 실행해준다.
CREATE USER IF NOT EXISTS 'debezium'@'%' IDENTIFIED BY 'debezium_password';
GRANT SELECT, RELOAD, SHOW DATABASES, LOCK TABLES, REPLICATION SLAVE, REPLICATION CLIENT
  ON *.* TO 'debezium'@'%';
FLUSH PRIVILEGES;
