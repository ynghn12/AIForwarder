# AITriggerForwarder

Scala 2.12 / Akka HTTP 기반 AI 트리거 포워더. AI Server의 `/ai_trigger` 요청을 받아 MongoDB에서 설비 정보를 조회한 뒤 EARS 서버에 시나리오 실행을 전달한다. 현재 Mongo 조회는 스텁으로 구현되어 있으며 추후 실제 Mongo 연결 정보로 교체해야 한다.

## 빌드
```bash
mvn -U clean package -DskipTests
```
산출물: `target/AITriggerForwarder-0.1.0-SNAPSHOT-jar-with-dependencies.jar`

## 설정
`src/main/resources/application.conf`에서 다음을 필요에 맞게 조정한다.
- `forwarder.interface` / `forwarder.port` (기본 0.0.0.0:50050)
- `forwarder.ears.url` (EARS 엔드포인트; 모킹 시 여기 변경)
- `forwarder.ears.timeout` (기본 5s)
- `forwarder.response-pass-status` (AI Server가 원하는 성공 status 값)

## 실행
```bash
java -jar target/AITriggerForwarder-0.1.0-SNAPSHOT-jar-with-dependencies.jar
```
로그는 콘솔로 출력(log4j2).

## 수동 테스트 시나리오
### 1) 정상 케이스
```bash
curl -X POST http://localhost:50050/ai_trigger \
  -H "Content-Type: application/json" \
  -d '{"eqpid":"1AFDE04_DU01","scname":"BC_FLOW_RECOVERY"}'
```
기대: HTTP 200, Body `{"status":"pass"}` (EARS가 2xx 응답 시)

### 2) 필수 필드 누락/빈 값
```bash
curl -X POST http://localhost:50050/ai_trigger \
  -H "Content-Type: application/json" \
  -d '{"eqpid":"","scname":""}'
```
기대: HTTP 500 (EARS 호출 생략)

### 3) EARS 장애/타임아웃 시뮬레이션
- `application.conf`의 `forwarder.ears.url`을 지연/에러를 내는 엔드포인트로 변경하여 5초 타임아웃 및 500 응답 동작 확인.

## 종료
- 터미널에서 `Ctrl+C`로 서버 종료. 로그에 "Shutting down AITriggerForwarder" 표시되면 정상 종료.

## 주의
- MongoDB EQP_INFO 조회는 현재 `StubEqpInfoRepository`로 대체되어 있음. 실제 스키마/커넥션 정보가 준비되면 교체 필요.
- 네트워크가 제한된 환경에서는 의존성 버전을 사내 Nexus에서 제공하는 것으로 맞춰야 함(현재 log4j 2.17.2, maven-compat 3.0 등).
