# SQream JDBC 6.6 DADP compatibility candidate 4

상태: 최종 동결 JAR의 실제 Wrapper 1,024회와 단일 JVM 연속 24,000회가 통과했다. **정식 배포 승인을 뜻하지 않는다.** 공급사 원본, 후보1~3 및 실패 증거를 보존한다.

## 변경 목적

SQL의 성공 응답을 받은 뒤 다음 실행을 준비하면서 이전 물리 연결의 closeConnection ACK를 무기한 기다리는 정체가 관찰됐다. ACK를 보내지 않는 제어 peer에서도 완전한47바이트 종료 프레임을 받은 뒤 동일 read 대기가 재현됐다. 실제 서버에서 그 ACK가 오지 않은 내부 원인 전체를 확인했다고 주장하지 않는다. 이 변경은 완료된 SQL의 데이터 확인과 연결 자원 종료를 분리한다.

- 엄격한 statementClosed ACK를 받은 연결 또는 statement를 열지 않은 연결은 선택적인 종료 제어 힌트를 보낸 뒤 로컬 채널을 닫는다. 원격 closeConnection ACK는 기다리지 않는다.
- plain 채널 힌트는 quiesce한 뒤 nonblocking write를 한 번만 시도한다. 완료 byte 수와 partial/error 상태를 기록한다. 로컬 write 완료가 원격 수신 확인을 뜻하지 않는다.
- TLS 채널에 평문 힌트를 보내지 않는다. underlying 채널을 먼저 닫고, quiesce 상태에서만 TLS wrapper 자원을 정리한다. 실제 SSL 운용 전체를 검증했다는 주장은 별도 시험이 필요하다.
- 활성·미확정·부분 조회 상태의 close는 추가 SQL/PUT 없이 로컬 취소한다. 자원 종료가 SQL 성공, 미확정 쓰기의 rollback, 또는 이미 보낸 데이터의 취소 완료를 뜻하지 않는다.
- terminal abort는 영구적이다. 짧은 lifecycle guard에서 새 raw 채널을 등록하고 blocking connect는 guard 밖에서 수행한다. 취소 뒤 새 채널 게시나 재open을 막으며 등록된 connect도 닫을 수 있다.
- 이전 heartbeat 세대를 quiesce한 뒤 재접속한다. 응답 read 중 기존10초 PING을 유지하고 binary 프레임 안에는 끼워 넣지 않는다. close 시 스레드를 인터럽트해 새 채널을 닫지 않는다.

## 데이터 완료와 close의 계약

JDBC close는 보류 중 bind나 batch를 실행하지 않는다. SQPreparedStatement.close와 ConnectorImpl.closeConnection에서 implicit flush를 제거했다. FlushService의 취소도 새 flush 없이 future.cancel(false)와 shutdown을 수행한다.

명시 executeUpdate의 정상 데이터 전송·ACK 순서는 유지한다. execute와 executeBatch는 자원 close에 실행을 미루지 않고 명시 실행 안에서 flush와 statement-close ACK를 끝낸 뒤 성공을 반환한다. executeBatch는 ACK 뒤에만 등록 목록을 비운다.

각 명시 DML은 실행 진입의 ACK sequence를 잡고, 같은 native statement-id에 대해 더 새로운 strict close ACK를 받아야 성공한다. 이전 실행의 ACK, 단순 phase=CLOSED, 또는 로컬 자원 종료만으로 성공을 만들지 않는다. 반대로 해당 실행에서 이미 받은 ACK는 뒤이은 자원 취소가 지우지 않는다. 첫 오류는 primary, 후속 정리 오류는 suppressed로 유지한다.

자동 재전송, SQL replay, 데이터소스 모드 전환, 평문 대체, 동시성 제한, 서버 timeout 상향 또는 서버 바이너리 변경을 포함하지 않는다.

## 지원 범위와 기존 공급사 제약

- SQream6.6 native DML statement는 실행 후 서버 statement가 닫힌다. 기존 Wrapper는 실행마다 새 native statement를 준비한다. 완료된 native statement의 재실행·addBatch는 데이터 전송 전에 명시 거부한다.
- executeBatch는 addBatch로 등록한 행만 처리한다. 등록 행이 없으면 현재 bind를 전송하지 않는다. 등록 batch 뒤 미등록 현재 bind가 섞인 호출은 자동 추가하지 않고 명시 거부한다. 현재 행을 addBatch로 명확히 등록한 뒤 실행한다.
- 공급사 Statement의 일부 update-count API는 실제 변경 뒤에도0을 반환한다. PreparedStatement batch의1 값도 기존 행별 반환 관례이며 모든 SQL의 정확한 affected-row 수라는 보장은 아니다. 검증은 독립 연결의 실제 행·암호문·복호문·중복으로 확인한다.
- 공급사 원본의 미지원·비표준 JDBC API 전체를 개선한 버전이 아니다. raw native API와 Hub-managed Wrapper의 기능 범위도 구분한다.
- 이미 buffer 한계를 넘어 공급사 addBatch가 stream 전송한 데이터까지 close가 되돌린다고 보장하지 않는다. 취소는 추가 전송과 재전송을 막으며 미확정 결과를 성공으로 바꾸지 않는다.

terminal 진단 상태는0=열림,1=힌트 전체 local write,2=partial,3=TLS 힌트 생략,4=힌트 오류 후 local release,5=active/unknown 취소,6=busy writer 취소,8=로컬 종료 오류다. 이 값은 자원 종료 상태이며 SQL 완료 판정과 별개다.

## 출처와 재현 빌드

공식 Maven Central com.sqream:sqream-jdbc:6.6을 기준으로 한다. 공식 배포 S3 JAR은 container SHA가 다르지만 압축 해제한1,320개 entry가 동일한 것이 확인됐다. 공개 sources JAR에 Java 소스가 없어 정확한 릴리스 bytecode8클래스를 CFR0.152로 복원했다. 공급사 소스 태그라고 주장하지 않는다. 원본 MIT 선언과 LICENSE/NOTICE를 보존하고 DADP-COMPAT-LICENSE.txt에 추가 고지를 둔다.

- 기준 SHA256: f4ed47c03620cc044cf35b80f25082712644a809a9a3bddac90ce42ce50b0198
- 후보4 SHA256: 4ad645dddd1fabe211bbc6d50ad3adb63b58c4528168fb81f4ac8fcebfe76733
- 복원 baseline SHA256: d48a9feb298c82435308d2d944c37c6fedb349a35d00f1b6be6014864c3da164

Python3와 JDK17, javac17.0.21.0.3으로 재현했다. 원본 native 클래스도 major61이므로 이 SQream 의존 JAR의 Java17 최소 기준을 올리지 않는다. 다른 DB용 Wrapper의 Java 기준을 변경하는 것은 아니다.

~~~sh
python3 compat/sqream-jdbc-6.6/candidate4/build.py --base-jar /absolute/path/to/sqream-jdbc-6.6.jar --output /absolute/path/to/external-output
~~~

--base-jar 생략 시 고정 Maven Central URL에서 원본을 받는다. 기준·16개 Java 소스·최종 두 JAR SHA를 검사하고 불일치 시 실패한다. 원본 덮어쓰기, 설치, 서비스 변경 또는 게시를 하지 않는다. 고정 재빌드가 같은 해시임을 확인했다.

## 검증 기록

최종 해시의 오프라인111개 검사를 통과했다. 실제 loopback의 no-ACK 종료, active/unknown 취소, 부분 힌트, reconnect 직전 취소, pending-close 전송0, 명시 batch1/3/128, execute, 실패 후 무재전송, stale/wrong/missing ACK 거부와 ACK 후 자원 종료를 포함한다. 원본8클래스의 public/protected descriptor와 static 계약을 보존했다.

sendParameters, executeQuery, 명시 Connector flush/close 및 FlushService.close의 정상 소스를 비교했다. executeUpdate에는 완료 상태 거부와 로컬 ACK postcondition만 더하며 정상 네트워크 데이터 순서를 바꾸지 않는다.

원시 API의 소규모 실물 검증에서133행·399암호문, close 전 fresh 연결 조회와 close 후 중복0, 미실행 close0행을 확인했다. 실제 Wrapper1,024회는 전체·부분 32세션 및 재사용 AppPS의 delegate 재준비를 포함하며 오류·중복0건과12개 무결성 검사를 통과했다. 이어 같은 최종 JAR로 단일 JVM의 전체·부분 × 1·8·32세션 CRUD24,000회가 모두 ACK, SQL시도 각1회, 실패·누락·중복·미확정0건으로 끝났다. 서비스·터널·설정·JAR 변경 없이36검사와50snapshot을 통과했고 독립 감시6,839샘플이STOPPED_OK로 종료했다. 동결 검증기도 통과했다. 오프라인·소규모 시험과 연속 실행은 별도 증적으로 보존한다.

최종 run ID: 7b08dbdf-1d72-4b49-a7ee-13eb5ba66e82. 증적 root는 /home/au212/projects/artifacts/dadp/verification/sqream-continuous-20261118이며 run-attempt3/acceptance.safe.json과 final-independent-audit.safe.json을 함께 확인한다. 상세 보고서는 dadp2.0 저장소 docs/sqream/2026-11-18-continuous-crud-validation.md다. 최종 판정 뒤 README만 갱신했으며 동결 Java/build 입력과 JAR 해시는 바꾸지 않았다.

테스트의 Unsafe 생성자 우회와 SecurityManager 실패 주입은 테스트 프로세스에만 사용한다. 런타임 JAR에는 테스트 클래스를 넣지 않는다.
