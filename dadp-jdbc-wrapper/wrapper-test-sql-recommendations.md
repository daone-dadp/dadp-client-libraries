# Wrapper 테스트용 SQL 추천 (30개)

> **기준**: [dadp-jdbc-wrapper-user-guide.md](./dadp-jdbc-wrapper-user-guide.md) 및 wrapper 테스트앱 엔티티/테이블  
> **테이블**: `users`, `orders` (init-databases.sql에서 생성). users 암호화 대상 예: `email`, `phone` (Hub 정책 매핑 시)

**목적**: 다양한 SQL이 **실행** 시 어떻게 처리되는지 결과를 보기 위한 예시.  
- **되는 SQL**: 현재 DB(`test_app_wrapper_db`)에서 **실행 자체가 성공**하는 완성된 SQL문. (암복호 적용 여부는 실행 방식에 따라 다름.)  
- **안 되는 SQL**: **쿼리 문법/대상 DB 오류** 등으로 **실행이 실패**하는 SQL문.

테스트앱의 SQL 테스트에서는 INSERT/UPDATE/DELETE를 포함한 모든 유형을 허용하고, 아래는 **그대로 복사해 실행할 수 있는 완성된 SQL문** 예시입니다.

---

## 1. 되는 SQL (실행 가능한 완성된 SQL) — 15개

DB 클라이언트(HeidiSQL 등) 또는 테스트앱 동적 SQL에서 **그대로 실행**하면 됩니다. 실행은 성공하고, Wrapper 경유 시 **Statement로 실행하면 INSERT/UPDATE는 평문 저장**, **SELECT는 ResultSet 복호화** 적용됩니다.

| # | SQL (완성된 문장, 그대로 실행 가능) | 용도 |
|---|-----------------------------------|------|
| 1 | `SELECT * FROM users LIMIT 1000` | 전체 조회 |
| 2 | `SELECT * FROM users WHERE id = 1` | 단건 조회 |
| 3 | `SELECT id, name, email, phone FROM users WHERE name = '홍길동'` | 이름으로 조회 |
| 4 | `SELECT email, phone FROM users LIMIT 10` | 암호화 컬럼만 조회 |
| 5 | `SELECT u.name, u.email FROM users u WHERE u.id = 2` | 별칭 사용 조회 |
| 6 | `INSERT INTO users (name, email, phone) VALUES ('테스트', 'test@example.com', '010-1234-5678')` | 회원 추가 |
| 7 | `INSERT INTO users (name, email, phone) VALUES ('김철수', 'kim@test.com', '010-1111-2222')` | 회원 추가 |
| 8 | `UPDATE users SET name = '이영희', email = 'lee@example.com', phone = '010-9999-8888' WHERE id = 1` | 단건 수정 |
| 9 | `UPDATE users SET email = 'new@example.com' WHERE id = 2` | 이메일만 수정 |
| 10 | `DELETE FROM users WHERE id = 999` | 단건 삭제 (id=999 없으면 영향 0행) |
| 11 | `SELECT * FROM users ORDER BY id DESC LIMIT 5` | 최신 5건 조회 |
| 12 | `SELECT COUNT(*) FROM users` | 건수 조회 |
| 13 | `SELECT name, email, phone FROM users WHERE id IN (1, 2, 3)` | IN 절 조회 |
| 14 | `SELECT * FROM users WHERE name LIKE '%길동%'` | 이름 부분 검색 |
| 15 | `SELECT id, name, email, phone, created_at, updated_at FROM users WHERE id = 1` | 전체 컬럼 단건 조회 |

---

## 2. 안 되는 SQL (실행 실패 — 쿼리/DB 문제) — 15개

아래는 **실행 자체가 실패**하는 예시입니다. 문법 오류, 존재하지 않는 테이블/컬럼, 타입/제약 위반 등으로 DB 또는 드라이버가 에러를 반환합니다.

| # | SQL (실행 시 오류 발생) | 실패 이유 |
|---|-------------------------|-----------|
| 16 | `SELECT * FROM user` | 테이블명 오타 (`users` 아님) |
| 17 | `SELECT * FROM nonexistent_table` | 존재하지 않는 테이블 |
| 18 | `SELECT id, nam FROM users` | 컬럼명 오타 (`name` 아님) |
| 19 | `INSERT INTO users (name, email, phone) VALUES ('홍길동')` | VALUES 개수와 컬럼 개수 불일치 |
| 20 | `INSERT INTO users (name, email, phone) VALUES ('a', 'b')` | VALUES 2개뿐, 컬럼 3개 |
| 21 | `INSERT INTO users (wrong_col) VALUES ('x')` | 존재하지 않는 컬럼 |
| 22 | `UPDATE users SET invalid_col = 'x' WHERE id = 1` | 존재하지 않는 컬럼 |
| 23 | `SELECT * FROM users WHERE id = 'not_number'` | DB/모드에 따라 타입 오류 가능 (MySQL은 암시적 변환으로 성공할 수 있음) |
| 24 | `DELETE FROM users WHERE id =` | 문법 오류 (값 누락) |
| 25 | `SELEC * FROM users` | 키워드 오타 (SELECT) |
| 26 | `SELECT * FROM users WHRE id = 1` | 키워드 오타 (WHERE) |
| 27 | `INSERT INTO users (id, name, email, phone) VALUES (1, 'a', 'b', 'c')` | PK 중복 시 제약 위반 (이미 id=1 존재하면 실패) |
| 28 | `SELECT * FROM users LIMIT 1.5` | LIMIT에 정수만 허용하는 DB에서 문법/타입 오류 |
| 29 | `SELECT * FROM users WHERE id IN ()` | IN () 빈 목록 — DB에 따라 문법 오류 |
| 30 | `SELECT * FROM users; DROP TABLE users;` | 다중 문 실행이 허용되지 않는 환경 또는 구문 오류 |

---

## 3. 암복호화와 실행 방식 (참고)

- **실행이 된다/안 된다**는 위처럼 “쿼리 자체가 정상인지, 실행이 성공하는지” 기준입니다.
- **암복호화**는 실행 방식에 따라 다릅니다.
  - **완성된 SQL 문자열**을 `Statement.executeUpdate(sql)` / `Statement.execute(sql)` 로 실행하면: INSERT/UPDATE는 **실행은 되지만** Wrapper가 값을 가로챌 수 없어 **암호화되지 않고 평문 저장**됩니다.
  - **PreparedStatement** + `?` + `setString`/`setObject(String)` 로 같은 로직을 실행하면 INSERT/UPDATE 시 **암호화 적용**됩니다.
  - **SELECT**는 Statement로 실행해도 Wrapper가 ResultSet을 래핑하므로 **복호화**됩니다.
- 따라서 “다양한 SQL이 어떻게 처리되는지” 보려면 테스트앱에서 **모든 유형(SELECT/INSERT/UPDATE/DELETE) 실행을 허용**하고, 위 **완성된 SQL문**을 그대로 넣어 실행해 보면 됩니다. 암호화가 안 되는 것은 “실행 실패”가 아니라 “실행 방식에 따른 동작 차이”입니다.

---

## 4. 엔티티/테이블 기준 (wrapper 테스트앱)

| 항목 | 내용 |
|------|------|
| DB | `test_app_wrapper_db` (MySQL) |
| 테이블 | `users`, `orders` |
| users 컬럼 | `id` (PK), `created_at`, `email`, `name`, `phone`, `updated_at` |
| orders 컬럼 | `id` (PK), `user_id`, `amount`, `status`, `created_at`, `updated_at` |
| 암호화 대상 예시 | `email`, `phone` (Hub 정책 매핑 시) |

---

## 5. orders 활용 (느린 SQL / 위험 SQL 테스트)

`orders` 테이블이 있으면 JOIN·서브쿼리·집계로 **느린 SQL·위험한 SQL** 패턴을 만들 수 있습니다. 아래는 그대로 실행 가능한 완성된 SQL 예시입니다. (데이터가 없으면 결과만 0건.)

| 용도 | SQL (완성된 문장) |
|------|-------------------|
| JOIN (실행 가능) | `SELECT * FROM users u INNER JOIN orders o ON u.id = o.user_id LIMIT 100` |
| JOIN + 조건 | `SELECT u.name, o.amount, o.status FROM users u JOIN orders o ON u.id = o.user_id WHERE o.amount > 1000` |
| 집계 (GROUP BY) | `SELECT u.id, u.name, SUM(o.amount) AS total FROM users u JOIN orders o ON u.id = o.user_id GROUP BY u.id, u.name` |
| HAVING | `SELECT u.id, u.name, SUM(o.amount) AS total FROM users u JOIN orders o ON u.id = o.user_id GROUP BY u.id, u.name HAVING SUM(o.amount) > 10000` |
| 서브쿼리 | `SELECT * FROM users WHERE id IN (SELECT user_id FROM orders WHERE status = 'PAID' AND amount > 5000)` |
| LIKE '%...%' (전체 스캔·위험) | `SELECT * FROM users WHERE name LIKE '%길동%'` |
| INSERT orders | `INSERT INTO orders (user_id, amount, status) VALUES (1, 9999.00, 'PAID')` |
| UPDATE (WHERE 있음) | `UPDATE orders SET status = 'CANCELLED' WHERE id = 1` |
| **위험** UPDATE (WHERE 없음) | `UPDATE orders SET status = 'CANCELLED'` |
| **위험** DELETE (WHERE 없음) | `DELETE FROM orders` |

- **위험** 표시 쿼리는 실행 시 전체 행이 영향받으므로, 테스트 후 데이터 복구가 필요하면 트랜잭션 롤백 또는 초기 데이터 재삽입을 고려하세요.

이 문서를 wrapper 테스트앱의 SQL 테스트(동적 SQL 실행) 및 DB 클라이언트 테스트 시 참고용으로 사용하면 됩니다.
