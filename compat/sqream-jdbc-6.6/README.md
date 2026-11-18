# SQream JDBC 6.6 DADP compatibility candidate

상태: **실험 후보이며 검증 미완료**. Wire 장애 주입, 실제 SQL 회귀, 설정·프로세스를 변경하지 않는 연속 24,000회 검증을 모두 통과하기 전에는 정식 배포 승인으로 해석하지 않는다. 공식 8.0.17 패키지와 원본 JDBC JAR은 변경하지 않는다.

## 변경 목적과 범위

JDBC 6.6의 이전 heartbeat가 쓰기 직전에 대기한 상태에서 재접속이 채널을 교체하면, shutdownNow()가 이전 스레드를 인터럽트해 새 채널까지 닫을 수 있다. 공식 원본 JAR의 제어된 타이밍 주입에서 ClosedByInterruptException과 새 채널 종료를 재현했다. 일반적인 긴 prepare와 PING 중첩만으로는 장애가 발생하지 않는 정상 제어군도 구분했다.

이 후보는 Messenger와 heartbeat의 전송 소유권을 공유한다. 이전 I/O가 끝난 뒤 heartbeat 세대를 무효화하고 채널을 교체하며, 재접속·statement 복원 및 binary frame 전송 중에는 heartbeat를 재개하지 않는다. 정지할 때 실행 중 I/O를 인터럽트하지 않고, 오래된 작업은 소유권 획득 후 세대를 다시 검사한다. 유휴 상태의 heartbeat 주기는 원본대로 유지한다.

전송 소유권을 10초 안에 얻지 못하면 이전 채널을 닫고 그 연결을 사용 불가로 처리한다. 성공 응답을 만들거나 SQL을 재시도하지 않는다. SQL replay, 동시성 제한, heartbeat 비활성화, 타이머 연장, SQream 서버 바이너리 변경을 포함하지 않는다.

기존 entry 1,320개 중 원본과 다른 항목은 MessengerImpl.class, HeartBeatServiceImpl.class, manifest 3개다. 추가 항목은 내부 CompatIo 클래스 및 라이선스·빌드 메타데이터이며 삭제는 없다. Wrapper의 기존 SQream Plugin용 실행별 PreparedStatement 생성 경로와 함께 평가한다.

## 원본과 라이선스

기준은 Maven Central의 com.sqream:sqream-jdbc:6.6이다.

- 원본 URL: https://repo.maven.apache.org/maven2/com/sqream/sqream-jdbc/6.6/sqream-jdbc-6.6.jar
- 원본 SHA256: f4ed47c03620cc044cf35b80f25082712644a809a9a3bddac90ce42ce50b0198
- 호환 후보 SHA256: 249a4dd56cc34035b09bce8986475d0a7ed0b8460db2d899f470097d04b37fbb
- 재컴파일 원본 제어군 SHA256: 5486c9de381db21cccb1813e03f9500cf83f447678e2c66eba98025f3a21884d

공식 POM은 MIT 라이선스를 선언한다. 원본 JAR의 META-INF/LICENSE와 META-INF/NOTICE를 보존하고 추가 고지를 포함한다. 공개된 6.6 sources JAR에는 Java 소스가 없었으며 공개 저장소의 6.6 소스 태그도 확인되지 않았다. baseline-source의 두 클래스는 정확한 릴리스 bytecode를 CFR 0.152로 복원한 것으로, 공급사 원본 소스 태그라고 주장하지 않는다. compat-src는 그 복원 기준에 대한 변경본이다.

## 재현 빌드

Python 3, JDK 17과 javac --release 8이 필요하다. 고정 후보는 javac 17.0.21.0.3으로 재현했다. 출력은 추적 소스 디렉터리 밖에 둔다.

~~~sh
python3 compat/sqream-jdbc-6.6/build.py --output /absolute/path/to/build-output
~~~

원본을 이미 보유한 경우 다음과 같이 고정 SHA를 검증하며 사용할 수 있다.

~~~sh
python3 compat/sqream-jdbc-6.6/build.py --base-jar /absolute/path/to/sqream-jdbc-6.6.jar --output /absolute/path/to/build-output
~~~

빌드 스크립트는 원본 SHA, 네 개 Java 소스의 해시, 최종 두 JAR의 SHA를 검사한다. 일치하지 않으면 실패한다. 원본 파일을 덮어쓰거나 설치·서비스 변경·게시를 하지 않는다. JAR에 포함되는 build-metadata.json과 baseline-build-metadata.json은 최초 후보 생성 당시의 정적 provenance를 그대로 보존한다. 그 안의 최초 실험 절대 경로는 실행 경로나 의존 경로가 아니다. 현재 빌드 경로와 컴파일러는 출력의 rebuild-receipt.json에 별도로 기록한다.

후보를 변경할 때는 새 버전과 검증 결과를 만들어야 하며, 고정 SHA 검사만 제거해 기존 후보 이름으로 대체하면 안 된다.

## 파일과 검증 경계

- baseline-source/: 정확한 원본 두 클래스의 복원 기준
- compat-src/: 호환 변경 소스
- sqream-jdbc-6.6-dadpcompat1.patch: 두 소스 차이
- apply_compat_source.py: Messenger 변경 생성 절차
- build.py: 원본·소스·출력 SHA를 고정한 재현 빌드
- CompatOwnershipTest.java: binary 전송 스레드 인계, fetch 수명, 소유권·종료 예외 검사
- CompatStartupFailureTest.java: heartbeat 부분 초기화 실패 정리와 재시작 검사
- DADP-COMPAT-LICENSE.txt: 추가 라이선스 및 provenance 고지

오프라인 검사는 14개 통과했다. CompatStartupFailureTest의 SecurityManager는 JDK 17 하위 테스트 프로세스에서 스레드 시작 실패를 주입할 때만 사용하며 운영 코드에 포함되지 않는다. 이 결과는 실제 SQream SQL, 장시간 연속 처리 또는 배포 승인을 대체하지 않는다. 최종 wire·실제 SQL·연속 실행 결과와 버전별 증거 연결은 해당 검증이 끝난 뒤 갱신한다.
