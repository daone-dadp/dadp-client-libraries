# JDBC Wrapper 8 착수

- Branch: `codex/db-plugin-v8`
- Wrapper version: `8.0.0-SNAPSHOT`
- Worktree: `/home/au212/projects/worktrees/dadp-db-plugin-v8/dadp-client-libraries`
- 상위 설계: `/home/au212/projects/worktrees/dadp-db-plugin-v8/docs/architecture/db-plugin-v8/README.md`

암복호화된 값을 바인딩/결과에 치환하는 방식에서 DB 암복호화 함수를 SQL AST의
대상 표현식에 삽입하는 방식으로 전환한다. Wrapper native/JNI local 암호화는
이번 목표가 아니다. 정책 매핑, parameter 위치, SQL dialect, alias, NULL과 batch
동작을 보존해야 한다. 검색·조인·집계는 별도 지원표와 시험을 먼저 작성한다.

현재는 버전과 작업공간만 준비했다. 실제 SQL 재작성은 미구현이다. parent와
공유 라이브러리는 6.1.3을 유지하며 JDBC artifact와 dependencyManagement의
해당 좌표만 8.0.0-SNAPSHOT으로 맞춘다. 상위 저장소와 별도로 commit/push한다.
