# DADP 8 Wrapper 개발 기준

- 브랜치: codex/db-plugin-v8
- 작업공간: /home/au212/projects/worktrees/dadp-db-plugin-v8/dadp-client-libraries
- JDBC: 8.0.0-SNAPSHOT, shared/parent: 6.1.3
- 상태: 목표 설계. SQL 재작성 기능 구현 완료가 아님.

## 반드시 읽을 정본

1. [아키텍처](/home/au212/projects/worktrees/dadp-db-plugin-v8/docs/architecture/README.md)
2. [Wrapper 책임](/home/au212/projects/worktrees/dadp-db-plugin-v8/docs/architecture/details/wrapper.md)
3. [상세 설계와 승인 gate](/home/au212/projects/worktrees/dadp-db-plugin-v8/docs/design/db-plugin-v8-design.md)
4. [담당별 작업 지시](/home/au212/projects/worktrees/dadp-db-plugin-v8/docs/kcmvp/work-orders/db-plugin-v8-work-order.md)

## 구현 범위

정책에 따라 SQL AST의 대상 표현식을 DB 암복호화 함수로 감싼다.
값을 Engine에 보내 치환하거나 ResultSet을 재복호화하지 않는다.
Wrapper JNI/native 모듈·키 수령·키 캐시를 추가하지 않는다.
기존 6.x/7.x 문서는 코드 조사용이지 이번 개발 지시가 아니다.

첫 DB/dialect와 함수 계약 확정 후 단순 INSERT/UPDATE와 SELECT projection부터 구현한다.
placeholder 개수/순서/타입, alias, NULL, batch, transaction, ResultSet metadata를 보존한다.
지원표 밖의 보호 SQL은 차단하고 평문 실행으로 fallback하지 않는다.
SQL 문자열 치환, 랜덤 암호문 equality 검색, 직접 DML 우회 방지 추정은 금지한다.
TLS 연결 옵션과 인증서 검증을 유지한다.
부분암호화는 DB 공통 runtime 책임이며 Wrapper가 데이터를 분할하지 않는다.

SQL golden tests와 실제 DB 직접 UDF↔Wrapper 교차 시험을 통과해야 완료다.
구현/시험/미지원 범위를 보고하고 독립 저장소에 commit/push한다.
