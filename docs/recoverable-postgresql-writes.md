# PostgreSQL 저장 요청 복구와 중복 방지

DADP JDBC Wrapper 8.0.13의 RecoverablePostgresqlWrite는 명시적으로 선택하는 API다. 일반 JDBC executeUpdate·commit을 자동 재실행하지 않는다. PostgreSQL17에서 승인된 단건 prepared INSERT·UPDATE·DELETE에 사용한다. Engine·Plugin은 기존 데이터소스 설정을 유지하며 자동 전환하지 않는다.

## 설치와 호출

DBA가 JAR의 com/dadp/jdbc/recovery/postgresql.sql을 대상 업무 DB에 한 번 적용한다. 승인 앱 역할에만 dadp_recovery 스키마 USAGE와 write_requests의 SELECT·INSERT·UPDATE를 부여한다. 앱에 스키마 소유권·DDL·권한 변경을 부여하지 않는다. RLS는 current_user별 요청을 격리한다. 앱이 read-only·키 DB 파일 권한을 변경하지 않는다.

~~~java
RecoverablePostgresqlWrite writes = new RecoverablePostgresqlWrite(
    jdbcUrl, jdbcProperties, "customer-orders", 65000);
RecoverablePostgresqlWrite.Result result = writes.execute(
    requestId, // 발신자가 유지하는 UUID. 재제출에도 같은 ID.
    "INSERT INTO public.users (name,email,phone) VALUES (?,?,?)",
    "id", name, email, phone);
~~~

jdbcProperties에는 기존 DB 인증·Alias·실행형 설정을 전달한다. Alias·정책 매핑·앱 mTLS가 먼저 준비돼 있어야 한다. Plugin형은 dadp.v8.schema/table/column/generated-key 설정을 전달한다. generatedKeyColumn은 INSERT의 단일 bigint 키일 때만 지정하며 UPDATE·DELETE는 null이다. affectedRows·generatedKey를 확인한다. replayed=true는 같은 DB 주체·scope·ID의 기존 커밋 결과다.

## 처리 계약

요청 해시는 scope·UUID·정확한 SQL·키 열·파라미터 타입과 값에 결합된다. 같은 ID의 입력이 다르면22023으로 거부한다. 파라미터는 ledger에 저장하지 않으며 해시는 암호화된 개인정보 자체가 아니다. String·정수·Boolean·BigDecimal·UUID·byte[]·JDBC Date/Time/Timestamp 및 null을 지원한다. stream·LOB·복수 SQL·인라인 문자열 리터럴은 지원하지 않는다. null 바인딩은 VARCHAR다.

ledger 선점·업무 쓰기·결과 기록은 같은 물리 PostgreSQL 트랜잭션으로 커밋한다. 중복 동시 요청은 유일 키로 직렬화된다. 커밋 응답을 잃으면 새 연결에서 ledger를 조회하며, 이미 커밋된 업무 SQL은 다시 실행하지 않는다. 미커밋 트랜잭션은 DB 롤백 후 다음 시도에서 다시 수행한다. 트리거를 포함해 부수 효과가 같은 DB 트랜잭션 안에 있어야 한다. 외부 HTTP·메일·비트랜잭션 FDW 등은 보장에 포함하지 않는다.

일시 통신 장애·관리자 종료·준비 중 DB·직렬화/교착 롤백·서버 읽기 전용25006·명시적 KMS 통신 장애DPT01만 제한적으로 재시도한다. 지수 대기와 jitter를 적용한다. 보안 거부·TLS·입력·제약·취소57014·일반38000은 반복하지 않는다. 대기 한도는1~600000ms이며 개별 네트워크/SQL 호출 때문에 실제 반환은 한도를 넘길 수 있다. 상위 앱 timeout을 함께 조정한다.

## 한도 초과와 복구

DPT02는 완료로 확인되지 않은 상태다. 성공 응답으로 바꾸거나 ID를 버리지 않는다. 앱/발신자는 원래 ID와 동일 입력을 유지하고 장애 복구 후 재제출해 결과를 대조한다. 새 ID는 다른 업무 요청이므로 중복 방지가 되지 않는다.

디스크 요청 큐는 제공하지 않는다. 완료 전 앱 프로세스가 사라지는 경우까지 보존하려면 앱 또는 상위 발신자의 영속 요청 기록이 필요하다. 이 전제 없이 무기한 무실패를 보장하지 않는다. ledger와 업무 DB를 함께 백업·복원하고 재제출 가능 ID가 남았을 때 ledger를 지우지 않는다. 같은 DB 역할·scope·입력 계약을 유지해야 기존 결과를 확인할 수 있다.
