# 학습 과제 ① - Virtual Threads로 I/O 바운드 처리량 개선

> study 제목: "Virtual Threads로 I/O 바운드 처리량 개선"
> 목표: `POST /api/ask`는 임베딩·검색·LLM 호출로 **대부분의 시간을 대기(blocking)** 한다.
> 플랫폼 스레드풀 대비 Virtual Threads로 동시 처리량과 p99가 어떻게 바뀌는지 **직접 측정**한다.

## 먼저 이해할 개념 (왜 이게 핵심인가)

- **플랫폼 스레드**는 OS 스레드 1:1 매핑이라 비싸다. 그래서 톰캣 기본 스레드풀은 200개로 제한된다.
  요청이 LLM 응답을 2초 기다리는 동안 그 스레드는 **묶여서 놀고 있다**. 동시 요청이 200을 넘으면 큐잉→지연 폭증.
- **Virtual Thread**(Project Loom, Java 21 정식)는 JVM이 관리하는 경량 스레드다. blocking 호출을 만나면
  JVM이 그 시점에 캐리어 스레드에서 **분리(unmount)** 하고 다른 작업을 얹는다. 그래서 수만 개를 띄워도 싸다.
- **핵심 통찰**: Virtual Threads는 CPU 바운드를 빠르게 하지 않는다. **I/O 대기가 많을 때** 처리량을 올린다.
  우리 `ask`는 정확히 그 케이스 → 좋은 데모.
- 함정: `synchronized` 블록 안에서 blocking하면 캐리어가 **pinning**되어 이점이 사라진다. `ReentrantLock` 사용.
  (면접 단골 질문 → README/스터디에 이 pinning 얘기를 넣으면 "깊이 안다" 인상)

## 무엇을 구현하나

Codex가 다음을 스텁으로 남겨둔다:
1. `config/ThreadConfig` - Virtual Threads on/off를 프로퍼티로 토글 (`app.virtual-threads.enabled`).
   - Spring Boot 3.3은 `spring.threads.virtual.enabled=true` 한 줄로 톰캣 executor를 가상 스레드로 바꿀 수 있다.
     이걸 프로퍼티로 켜고 끄며 A/B 비교할 수 있게 한다.
2. `bench/` - 부하 비교 도구. **k6 스크립트**(`bench/ask-load.js`)로 동시 사용자(VU) 수를 올리며
   처리량(req/s)과 p95/p99 응답시간을 뽑는다.

### 당신의 과제
- [ ] `application.yml`에서 가상 스레드 토글을 실제로 배선하고, LLM 호출이 blocking일 때
      톰캣 스레드가 어떻게 동작하는지 로그/메트릭으로 확인한다.
- [ ] pinning을 피했는지 점검: RAG 경로에 `synchronized` blocking이 없는지 확인.
- [ ] k6로 **동일 부하를 두 번**(가상 스레드 off/on) 돌려 결과를 표로 정리한다.
      - 예: VU 200, 60초. `http_reqs`, `http_req_duration p95/p99`, 에러율 비교.
- [ ] `-XX:+UnlockDiagnosticVMOptions -Djdk.tracePinnedThreads=short`로 pinning 발생 여부 확인(선택).

## 검증 (스터디에 넣을 숫자)

```bash
# 가상 스레드 OFF
SPRING_THREADS_VIRTUAL_ENABLED=false docker compose up -d app
k6 run bench/ask-load.js
# 가상 스레드 ON
SPRING_THREADS_VIRTUAL_ENABLED=true docker compose up -d app
k6 run bench/ask-load.js
```

정리 예시(스터디 metric): `동시 200 질의 처리량 45 req/s → 120 req/s`, `p99 4.1s → 1.3s`.
(숫자는 로컬 Ollama 성능에 좌우되니 **당신 머신의 실제 측정치**를 쓴다. 방향성과 해석이 핵심.)

## 학습 확인 질문 (면접 대비)
1. Virtual Thread가 blocking을 만나면 실제로 무슨 일이 일어나나? unmount/mount를 설명해보라.
2. 왜 CPU 바운드에는 효과가 없나?
3. pinning은 언제 발생하고 어떻게 피하나?
4. 그냥 스레드풀을 2000개로 늘리면 안 되나? (메모리·컨텍스트 스위칭 비용으로 답)
