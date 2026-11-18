# SQream JDBC 6.6 DADP compatibility candidate 3

상태: 최종 해시의 진단·연속 실행 검증 중. **정식 배포 승인이나 연속 24,000회 PASS를 의미하지 않는다.** 공식 8.0.17, 공급사 원본, 후보1 및 후보2를 보존한다.

## 수정과 경계

이 후보는 두 전송 문제와 첫 오류 보존을 함께 다룬다.

- 이전 heartbeat를 shutdownNow로 중단하면서 재접속한 새 채널을 닫는 race를 막는다. 이전 write가 끝난 뒤 세대를 무효화하고 채널을 교체하며 인터럽트하지 않는다.
- 전경 명령 순서와 write 소유권을 분리한다. prepare, PUT ACK, 활성 statement close 및 JSON/binary 응답 read 동안 기존 10초 heartbeat를 유지한다. PUT부터 binary header·전체 데이터 전송까지는 heartbeat가 끼어들지 않는다.
- PUT ACK 경계는 공급사6.6의 동일 FlushService 스레드가 put/header/전체 physical data/isPutted를 순서대로 호출하는 계약을 따른다. API 사이에 ReentrantLock을 넘기거나 다른 스레드가 해제하지 않는다. 공급사 JAR의 sendBinaryHeader 호출자는 SqreamExecutedStatementImpl.put 한 곳이다.
- reconnect/statement reconstruct handshake에서는 heartbeat를 멈추고 write 세대를 quiesce한 뒤 재개한다. 연결 전체를 닫는 terminal closeConnection handshake도 heartbeat를 차단한다. 활성 statement의 close 응답 대기와 구분한다.
- PUT, fetch, heartbeat 재개 및 terminal connection cleanup에서 첫 오류를 primary로 유지하고 이후 정리 오류는 suppressed로 남긴다. 정상 전송·종료 순서를 유지한다.

SQL replay, 자동 재시도, 동시성 제한, heartbeat 비활성화, 주기 연장, 서버 timeout 상향 또는 서버 바이너리 변경을 포함하지 않는다. write 소유권을 10초 안에 얻지 못하면 이전 transport를 닫고 사용 불가로 처리하며 새 채널을 만들지 않는다.

## 원인 증거와 검증 상태

원본 JDBC의 제어된 타이밍 주입에서 이전 PING이 새 채널을 ClosedByInterruptException으로 닫는 race를 재현했다. 이후 연속 실행은 다른 오류로 실패했다. 단순 EOF처럼 보였지만 실제로는 PUT ACK에서 먼저 54바이트 서버 오류를 받은 뒤 cleanup EOF가 이를 덮었다. 후보2로 첫 오류를 보존한 결과 고정 오류 문구 Session ended: client timeout (60 seconds)를 확보했다.

준비 후15초 입력 대기는 정상 처리됐고 binary 선언 길이와 실제 전송량도 일치했다. 75초의 긴 statement-close 대기 자체는 원본과 후보2 모두 처리했다. 별도의 명시적 PUT-header-read 직전75초 client pause 대조에서는 원본이 PING을 유지하며 성공하고 후보2는 동일 timeout으로 실패했으며, 후보3의 초기 해시는 성공했다. 이 인위적 pause와 실제 raw32의 ACK 대기는 구분한다. 최종 해시는 오류 정리 보완 후 같은 제어와 무계측·연속 실행으로 다시 검증한다.

오프라인 검사45개는 통과했다. 후보2는 blocking-read 중 heartbeat 유지 검사에서 의도대로 실패하는 음성 대조로 사용했다. 공급사 공개 메서드 descriptor/static 계약은 전부 보존됐고 세 번째 클래스의 private 포함 ABI도 동일하다. 이 결과는 실제 DB나 연속 실행 증거를 대체하지 않는다.

## 출처와 재현 빌드

기준은 Maven Central com.sqream:sqream-jdbc:6.6이다. 공급사 공식 다운로드 JAR은 컨테이너 해시가 다르지만 압축 해제한1,320개 entry가 동일한 것도 별도로 확인됐다. 공개 sources JAR에는 Java 소스가 없어 정확한 릴리스 bytecode 세 클래스를 CFR0.152로 복원했다. 공급사 소스 태그라고 주장하지 않는다. 원본 MIT 선언과 LICENSE/NOTICE를 보존하고 DADP-COMPAT-LICENSE.txt에 추가 고지를 둔다.

- 원본 SHA256: f4ed47c03620cc044cf35b80f25082712644a809a9a3bddac90ce42ce50b0198
- 후보3 SHA256: e4f4ee6ab99027fad03e5a1a36e185edbe152068cd5b1f66883f62cd9fe39a9e
- 복원 baseline3 SHA256: 9ab3cf770fca84d1dec65e7ba24b5493c61bc92a97dbc41290550248acaeb40a

Python3와 JDK17, javac --release8을 사용한다. 검증 컴파일러는 javac17.0.21.0.3이다.

~~~sh
python3 compat/sqream-jdbc-6.6/candidate3/build.py --base-jar /absolute/path/to/sqream-jdbc-6.6.jar --output /absolute/path/to/external-output
~~~

--base-jar를 생략하면 고정 공식 Maven Central URL에서 받아 SHA를 검증한다. 원본·여섯 Java 소스·최종 두 JAR의 SHA가 다르면 실패한다. 원본 덮어쓰기·설치·서비스 변경·게시를 하지 않는다. 재빌드 해시 일치를 확인했다.

baseline-source와 compat-src에는 버전별 소스를, patch에는 차이를 보존한다. Java8의 Buffer.position 반환형 때문에 복원본 한 곳에 넣은 ByteBuffer cast는 baseline과 candidate에 동일하게 적용했다. FullDuplexLivenessTest, CompatOwnershipTest, CompatStartupFailureTest, PutFailurePreservationTest가 각각 read/PING·frame·세대 경계, 소유권·정리, 부분 초기화 실패, PUT/fetch 첫 오류를 검사한다. 테스트 전용 짧은 scheduler interval과 SecurityManager 실패 주입은 운영 코드의 10초 heartbeat 설정을 바꾸지 않는다.
