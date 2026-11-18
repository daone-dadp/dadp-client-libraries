# JDBC 6.6 DADP compatibility candidate 2

상태: 검증 중인 별도 후보. 이 변경은 PUT의 첫 실패를 종료 과정의 실패가 덮는 진단 결함을 해결한다. **실제 최초 54바이트 서버 오류의 원인은 계속 조사 중이며, 연속 24,000회 통과를 의미하지 않는다.**

원본 6.6과 기존 dadpcompat1 후보249a 및 그 증거는 그대로 보존한다. 이 디렉터리는 독립된 세 클래스 복원 기준과 수정본을 담는다. MessengerImpl/HeartBeatServiceImpl 소스는 후보1과 동일하다. 추가 변경은 SqreamExecutedStatementImpl.put의 ConnException 처리뿐이다. 기존 close 시도를 유지하고, 실패하면 첫 ConnException의 suppressed에 종료 오류를 넣은 뒤 기존 RuntimeException 래핑 계약을 유지한다. 정상 PUT/header/data/ACK/close 순서와 공개·비공개 필드/메서드 ABI는 유지된다. SQL 재시도나 성공 응답 대체는 없다.

## 기준과 재현

기준 JAR은 Maven Central com.sqream:sqream-jdbc:6.6, SHA256 f4ed47c03620cc044cf35b80f25082712644a809a9a3bddac90ce42ce50b0198이다. 공개 sources JAR에 Java 소스가 없어 정확한 릴리스 class를 CFR0.152로 복원했다. 공급사 소스 태그라고 주장하지 않는다. 원본 MIT 선언과 LICENSE/NOTICE를 보존하며 추가 고지는 DADP-COMPAT-LICENSE.txt에 있다.

세 번째 클래스 복원본은 Java8의 Buffer.position 반환형과 Java17의 공변 ByteBuffer 반환형 차이를 처리하도록 해당 호출 한 곳에 명시적 ByteBuffer cast를 넣었다. 수신 객체·위치 인자·동작은 같으며 baseline과 candidate 모두 같은 복원 조정을 적용한다.

Python3 및 JDK17, javac --release8을 사용한다. 검증 컴파일러는 javac17.0.21.0.3이다.

~~~sh
python3 compat/sqream-jdbc-6.6/candidate2/build.py --base-jar /absolute/path/to/sqream-jdbc-6.6.jar --output /absolute/path/to/external-output
~~~

--base-jar를 생략하면 고정 공식 Maven Central URL에서 원본을 받아 SHA를 검증한다. 원본·여섯 Java 소스·최종 두 JAR의 SHA가 다르면 빌드가 실패한다. 설치·게시·서버 변경을 하지 않는다.

- 후보2 JAR SHA256: 2e6e631d9067bea885e186b13ce1e843aca93a7294752e0427231df2be32c13a
- 복원 baseline3 JAR SHA256: 8722d9624ffffa7e34b4ddaa8779841257688bfb34c5cf9b81070224805fb97e

PutFailurePreservationTest는 첫 오류와 cleanup 오류의 동시 발생, 정상 cleanup, 정상 PUT 및 명시적 close 순서, cleanup Error를 검사한다. 후보2에서 10개 검사를 통과하고 복원 baseline은 첫 오류 보존 검사에서 의도대로 실패했다. 세 번째 클래스는 원본과 javap -p -s 전체 ABI가 동일하다. 실제 DB 오류 응답과 장기 연속 검증은 별도 증거가 필요하다.
