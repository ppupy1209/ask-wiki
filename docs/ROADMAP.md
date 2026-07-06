# ROADMAP — 운영 수준 딥다이브 (Phase B·C)

> **원칙: 한 번에 한 딥다이브.** 순서는 **B1 → B2 → C1 → C2 → C3** (W만 병행 허용).
> 앞 단계가 "실무·운영 수준"으로 끝나기 전에 다음을 시작하지 않는다. 단순 구현이 아니라 **각 phase가 재현 테스트·측정·판단 기록을 남기는 deep dive**여야 한다.
> (2026-07-07 보강: 목표 기업 채용 공고 요구에 맞춰 C1~C3·W를 정식 편성 — 아래 "채용 공고 대응" 참조.)

---

## 채용 공고 대응 (2026-07-07 계획 보강)

목표 공고의 요구 ↔ phase 매핑. 이미 가진 것: RAG 파이프라인(Phase 2·A), 속도 최적화 실측(VT 2.3배·캐시 8s→0·인덱스 256배).

| 공고 요구 | 대응 phase |
|---|---|
| 검색 품질 최적화 / 성능(정확도) | **B2** — hit rate·환각률 하네스 |
| 프로덕션 운영 (정합성·장애 안정성) | **B1** — 유령 재현→해결→측정 |
| 벡터 DB 기반 RAG | **C1** — 이행 + B1 패턴 이식 + B2 검증 |
| 상용 LLM 연동 (Claude/GPT/Gemini) + 토큰 최적화 | **C2** — 프로바이더 스위치 + 토큰·비용 관측 |
| AI 에이전트 (LangChain/LangGraph 등) | **C3** — 에이전틱 RAG |
| Claude Code 활용 전문가 (서브에이전트·MCP·훅) | **W** — 워크플로우 아티팩트화 (병행) |

- **C1 벡터 DB 이행** (구 백로그 B6 승격): 기술 선택(Qdrant·pgvector·ES kNN 비교) → B1 동기화 패턴 이식 → **B2 하네스로 이행 전후 품질 동등성·성능 비교**. "붙여봤다"가 아니라 "이행하며 정합성을 지켰고 회귀 없음을 증명했다"가 목표.
- **C2 상용 LLM 프로바이더 스위치**: Spring AI 추상화로 Ollama ↔ Claude/GPT/Gemini 전환. 토큰·비용 Micrometer 지표(공고의 "토큰 최적화"), 타임아웃·세마포어·degraded 폴백(구 B4 흡수), B2로 프로바이더별 품질·비용 비교. 부수 효과: 저비용 운영 배포 가능(백로그 배포 과제 해소). B3(SSE first-token)도 여기서 결합 검토.
- **C3 에이전틱 RAG**: 단발 검색→답변을 넘어 검색 필요 판단·질문 재작성·다중 검색 루프. 프레임워크(Spring AI tool-calling vs LangChain4j)는 착수 시 비교·결정 — LangChain/LangGraph 키워드에는 "동일 개념을 자바 생태계로 구현 + 원리로 설명" 전략으로 대응. 개선은 반드시 B2 하네스로 증명.
- **W Claude Code 워크플로우 아티팩트화 (병행)**: 이미 실천 중인 것을 레포에 보이게 만든다.
    - [ ] W-1: `CLAUDE.md` + 커밋 전 금칙어 검사 훅
    - [ ] W-2: 커스텀 커맨드 (세션 시작 브리핑·진행 기록)
    - [ ] W-3: 서브에이전트 활용 사례 기록 (Codex 위임 — GhostIndexTest 작성 사례)
    - [ ] W-4: "AI 페어 워크플로우" 포트폴리오 글감 (step-by-step 프로토콜 = START-HERE §7)
>
> **진행 방식**: 설계 선택과 핵심 구현은 **연우님이 직접**(학습 목적). AI(Claude/Codex)는 설계 논의 상대,
> 보일러플레이트·테스트 데이터·문서 정리 담당. 기존 study와 같은 규칙 — **실측만 기록, 측정 환경 명시, 재현 스크립트 커밋.**
>
> **직접 구현 구간은 반드시 step-by-step** (START-HERE §7): AI는 완성 코드를 던지지 않고
> "목표·이유 → 타이핑할 코드(작은 단위) → 확인 방법"을 한 단계씩 안내하고, 연우님이 직접 치며 진행한다.
> 진척·판단·측정은 그때그때 START-HERE 진행 로그와 design-notes.md에 기록한다.

---

## Phase B1 — 증분 인덱싱과 정합성 (딥다이브 ①)

### 문제 (지금 코드의 실제 약점)

- `DocumentService.create()`는 청크를 저장하며 `vectorIndex.add()`로 즉시 반영하지만, **트랜잭션이 롤백되면?**
  DB에는 없는 청크가 인덱스에 남는다 (인덱스 반영이 트랜잭션 밖 부수효과).
- `DocumentService.delete()`는 `vectorIndex.rebuild()` — 문서가 많아지면 삭제 한 번에 전체 재빌드 비용.
  재빌드 도중 들어온 검색 요청은 무엇을 보나? (가용성 vs 정합성)
- 앱 재시작 시점과 DB 상태가 어긋나는 창(window)은 없나?

**한 줄 정의: "DB 커밋과 벡터 인덱스 갱신의 원자성·정합성을 어떻게 보장하는가."**
— 이건 인기글 스터디에서 배운 **"DB 커밋과 Kafka 발행의 원자성"과 동일한 문제 구조**다.
배운 패턴(Transactional Outbox)을 자기 제품의 실제 문제에 전이하는 것이 이 딥다이브의 핵심 서사.

### 설계 선택지 (직접 비교하고 결정할 것)

| 선택지 | 장점 | 단점 | 판단 포인트 |
|---|---|---|---|
| A. 동기 갱신 (현재) | 단순, 즉시 반영 | 롤백 시 유령 엔트리, 재빌드 블로킹 | 트랜잭션 경계와 인덱스 갱신이 분리돼 있음을 직접 재현해볼 것 |
| B. `@TransactionalEventListener(AFTER_COMMIT)` | 커밋 후에만 반영 | 발행 실패 시 유실 (보정 경로 없음) | 인기글 스터디의 "즉시 발행" 경로와 동일한 한계 |
| C. **Outbox + 폴링 relay** | 커밋과 원자적, 실패 시 재처리 | 반영 지연(폴링 주기), 테이블 추가 | 스터디에서 배운 2단 발행 구조의 재적용 |
| D. Kafka | 확장성, 재처리 | 단일 인스턴스에 브로커 운영은 과함 | **"왜 안 썼나"를 문서로 남기는 것 자체가 포트폴리오 재료** (MQTT 선택 이유와 같은 서사 구조) |
| E. 파생 데이터 관점 (버전 스탬프 + 주기 diff/재빌드) | 인덱스는 DB에서 언제든 재구성 가능한 **파생 데이터** — 유실이 치명적이지 않음 | 반영 지연, 전체 스캔 비용 | Kafka 발행(데이터가 시스템 밖으로 나감)과 정합성 요구가 **다르다**는 걸 인지하는 것 자체가 딥다이브 포인트. Outbox와 이 관점을 비교·결합하는 논의가 인기글 스터디와의 차별화 지점 |

삭제·재빌드는 별도 축: **세대(generation) 인덱스 스왑** — 새 인덱스를 백그라운드로 만들고
완성되면 참조를 원자적으로 교체(AtomicReference). 재빌드 중에도 이전 세대로 검색 가능(무중단).

### 과제 (직접 구현)

- [ ] 현재 구조의 정합성 깨짐을 **테스트로 먼저 재현** (롤백 후 인덱스에 유령 청크가 남는 것을 증명)
- [ ] 선택지 A~D 비교표를 채우고 결정 + 근거를 `docs/design-notes.md`에 기록
- [ ] 선택한 구조 구현 (Outbox라면: outbox 테이블 V2 마이그레이션, relay, 멱등 처리 — 같은 이벤트 재적용에 안전하게)
- [ ] 삭제 경로: 세대 스왑 구현, 재빌드 중 검색 가용성 확인
- [ ] 장애 시나리오 검증: relay 죽였다 살리기 → 유실 0건 확인

### Step 1 세부 진행 — 유령 인덱스 재현 테스트 (세션 이어가기용 step-by-step 기록)

> 테스트 DB는 **Testcontainers**(전용 MySQL 8.4 컨테이너 — 빈 DB라 절대값 단언, 호스트 포트 충돌 무관, CI 편입 가능).
> **재현 시나리오**: 3청크 문서를 `DocumentService.create()`로 넣되, 목(mock) `EmbeddingClient`가 **3번째 청크에서 예외**
> → `@Transactional` 롤백으로 DB는 document/chunk 0건, 그러나 `vectorIndex.add()`는 트랜잭션 참여자가 아니라 앞 청크 2건이 인덱스에 잔류(유령).
> 테스트는 "롤백 후 `vectorIndex.size() == 0`"이라는 **원하는 불변식**을 단언 → 현 구조에서는 **빨간 불이 정상**이고, Step 3 수정 후 초록 전환이 B1의 증명이 된다.

- [x] 1-0 툴체인: 윈도우 PC IntelliJ + JDK 21 (Gradle sync 성공 확인 — 테스트 실행은 1-2가 최종 검증을 겸함)
- [x] 1-1 의존성: `spring-boot-testcontainers` + `testcontainers:junit-jupiter` + `testcontainers:mysql` 추가 (버전은 Boot BOM 관리)
- [x] 1-2 골격: `src/test/java/com/yeonwoo/askwiki/document/GhostIndexTest.java` — `@SpringBootTest` + `@Testcontainers` + static `@Container @ServiceConnection MySQLContainer("mysql:8.4")` + `@MockBean EmbeddingClient` + 스모크 테스트(부팅 후 `vectorIndex.size() == 0`) 초록 확인 — 1-2·1-3은 연우님 결정으로 **Codex CLI가 작성**, Claude가 검토·실행
- [x] 1-3 재현 본문: given 3청크짜리 텍스트(청커 규칙: 공백 경계 500자·오버랩 50자 → 약 1,300자 필요), 목 임베딩이 청크1·2는 정상 벡터/청크3은 예외 → when `create()`가 예외로 종료 → then document·chunk 테이블 0건(롤백 확인) + `vectorIndex.size() == 0`(불변식 단언 → 빨간 불 기대)
- [x] 1-4 실행·기록 (2026-07-06): **빨간 불 재현 성공 — 유령 엔트리 2건 실측** (`expected: <0> but was: <2>`), document·chunk는 롤백으로 0건, 스모크는 초록. 재현 테스트 자체는 0.36s. ⚠️ `gradlew test`는 Step 3 수정 전까지 의도적으로 red (bootJar·도커 이미지 빌드는 테스트를 안 돌려 영향 없음).

> **테스트 인프라 트러블슈팅 (윈도우 PC, 2026-07-06)** — 재발 시 참고:
> ① `gradle test`가 "No tests found": build.gradle에 `useJUnitPlatform()`이 없었음(그동안 IntelliJ 자체 러너가 가려줌) → `test` 블록 추가.
> ② Testcontainers "Could not find a valid Docker environment" + Status 400(빈 Info 응답): docker-java가 구식 `/v1.32` API로 호출하는데 Docker Engine 29(최소 API 1.40)가 400으로 거부. 네임드 파이프에 원시 HTTP를 보내 진단(`/v1.32/info`→400, `/v1.44/info`→200). 해결: Testcontainers 1.21.3 오버라이드 + 테스트 JVM에 `api.version=1.44` 시스템 프로퍼티(build.gradle에 커밋 — 다른 PC에도 적용) + 이 PC의 `~/.testcontainers.properties`에 `docker.host=npipe:////./pipe/dockerDesktopLinuxEngine`(desktop-linux 컨텍스트와 일치, 머신 로컬 설정).
> ③ IntelliJ에서 이 테스트를 돌릴 땐 Run tests using: **Gradle**(기본값)이어야 ②의 시스템 프로퍼티가 적용됨.

### Step 2 완료 — 동기화 방식 결정 (2026-07-06)

- [x] 선택지 A~E 비교·결정: **C(Outbox+relay) 메인, B는 비교·E는 보정 관점** — 근거는 design-notes.md §3 "결정 — 동기화 방식".
- [x] 부수 결정: 파생 데이터를 인메모리에 유지(공유 벡터 저장소는 C1로 이월), 삭제·재빌드(세대 스왑)는 별도 축으로 분리.

### Step 3 세부 진행 — Outbox + relay 구현 (step-by-step, 연우님 직접 타이핑)

> **핵심 아이디어**: `create()`가 `vectorIndex.add()`(트랜잭션 밖 부수효과)를 하는 대신, **같은 트랜잭션 안에서 `index_outbox` 테이블에 이벤트를 INSERT**한다.
> 롤백되면 이벤트도 함께 롤백 → 유령 원천 차단. 별도 relay(폴링 스케줄러)가 PENDING 이벤트를 읽어 인덱스에 반영하고 PROCESSED로 마킹. 재처리에 안전하도록 멱등.
> **create/add 경로(유령 문제)를 먼저**, 삭제(세대 스왑)는 3 후반.

- [ ] 3-1: V2 Flyway 마이그레이션 — `index_outbox` 테이블 (status 인덱스 포함)
- [ ] 3-2: `IndexOutboxEvent` 엔티티 + `IndexOutboxRepository` (PENDING 조회 파생 쿼리)
- [ ] 3-3: `create()` 수정 — `vectorIndex.add()` 제거, 같은 트랜잭션에서 outbox INSERT. → `GhostIndexTest` **초록 전환**(롤백 시 이벤트도 롤백돼 인덱스 0) + 신규 "커밋 후엔 이벤트가 PENDING으로 남는다" 단언
- [ ] 3-4: relay — `@Scheduled` 폴링, PENDING 읽어 인덱스 반영 후 PROCESSED. 멱등(이미 인덱스에 있으면 skip 또는 재적용 안전)
- [ ] 3-5: 테스트 — ① 해피패스(커밋 후 relay가 결국 반영 = eventual consistency) ② 멱등(같은 이벤트 2회 처리해도 중복 없음) ③ relay-kill(반영 전 크래시→재기동 시 유실 0)
- [ ] 3-6: 삭제 경로 — 세대 인덱스 스왑(AtomicReference), 재빌드 중 검색 가용성 확인
- [ ] 3-7: 측정 — 유령 N→0 확정, 업로드→검색 반영 지연 평균/최대 ms(폴링 주기 관계)

### 측정할 숫자 (before → after)

- 롤백 시나리오에서 유령 인덱스 엔트리: **N건 → 0건**
- 문서 갱신 → 검색 반영 지연: **평균/최대 ms** (폴링 주기와의 관계 해석)
- 삭제 시 검색 불가 시간: **재빌드 시간 전체 → 0** (세대 스왑)

### 학습 확인 질문 (면접 대비)

1. 인덱스 반영을 트랜잭션 안에서 하면 왜 안 되나? 밖에서 하면 무엇이 깨지나?
2. AFTER_COMMIT 리스너만으로 부족한 이유는? Outbox가 그걸 어떻게 보완하나?
3. 멱등성은 어디서 왜 필요한가? (relay 재처리 시 같은 이벤트가 두 번 오면?)
4. 이 구조에서 Kafka가 필요해지는 시점은 정확히 언제인가?

---

## Phase B2 — 답변 품질 평가 하네스 (딥다이브 ②)

### 문제

- 청킹 크기를 바꾸면? topK를 바꾸면? 프롬프트를 바꾸면? — 지금은 **좋아졌는지 나빠졌는지 알 방법이 없다.**
- RAG의 회귀(regression)는 컴파일 에러가 안 난다. 품질을 숫자로 만들어야 개선이 가능하다.
- "AI 품질을 회귀 테스트한다"는 주니어 포트폴리오에서 거의 없는 차별화 포인트.

### 설계

- **Golden set**: 평가용 문서 셋(사규·매뉴얼 샘플, `samples/`) + 질문 50개
  - 문서에 답이 **있는** 질문 30개 (기대 답 + 기대 출처 documentId 명시)
  - 문서에 답이 **없는** 질문 20개 (기대: "모른다" — 환각 억제 검증)
- **지표** (각각 왜 이 지표인지 설명할 수 있어야 함)
  - Retrieval hit rate@K: 기대 출처가 topK 검색 결과에 포함된 비율 — **검색 품질** (LLM과 무관하게 측정 가능)
  - 환각률: 답 없는 질문에 지어내서 답한 비율 — **프롬프트/생성 품질**
  - 인용 정확도: 답변의 sources가 실제 근거 문서를 가리키는 비율
- **러너**: JUnit 태그(`@Tag("eval")`) 또는 별도 CLI 러너로 golden set 일괄 실행 → 지표 리포트 출력.
  검색 지표(hit rate)는 LLM 없이도 돌므로 빠르고 결정적 — CI에 넣기 좋다. 생성 지표는 로컬에서 주기 실행.

### 과제 (직접 구현)

- [ ] golden set 설계·작성 (질문 선정 기준을 문서로 — 쉬운/어려운/모호한 질문 섞기)
- [ ] hit rate@K 측정 러너 구현 (LLM 미사용, 결정적)
- [ ] 환각률·인용 정확도 측정 러너 구현
- [ ] **실험 매트릭스**: 청크 크기(예: 200/400/800자) × topK(2/4/8)로 hit rate 비교 → 표로 기록
- [ ] 결과를 바탕으로 기본값 재결정 + 근거 기록

### 측정할 숫자

- hit rate@4: 기준선 **N%** → 튜닝 후 **M%** (무엇을 바꿔서 올랐는지가 핵심)
- 환각률: **N% → M%** (프롬프트 개선 전후)

### 학습 확인 질문 (면접 대비)

1. 검색 품질과 생성 품질을 왜 분리해서 측정하나?
2. 청크가 크면/작으면 hit rate와 답변 품질에 각각 어떤 영향?
3. 평가를 CI에 넣을 때 LLM 호출 지표는 왜 빼거나 분리하나? (비용·비결정성)
4. hit rate가 높은데 답변이 틀린다면 어디를 의심하나?

---

## 백로그

- **B3** SSE 토큰 스트리밍 — 8~15초 응답의 체감 개선, p95 first-token 측정 (chipthrone SSE 경험 재활용) → **C2에서 결합 검토**
- ~~**B4** 장애 격리~~ → **C2에 흡수** (LLM 타임아웃·세마포어·degraded 폴백·부하 중 kill 실험)
- **B5** 하이브리드 검색 — MySQL fulltext + 벡터 RRF 결합 (반드시 B2 하네스로 개선 증명)
- ~~**B6** 공유 벡터 저장소 이행~~ → **C1로 승격** (2026-07-07, 채용 요건 트리거 — design-notes §3 후속 갱신 참조)
- ~~운영 배포~~ → **C2에 흡수** (상용 API 스위치가 생기면 저비용 단일 인스턴스 배포 가능)
- compose 잔여 리네임 — DB명/계정 `ragdoc`은 볼륨 호환 위해 유지 중. 갈아탈 때 볼륨 재생성 필요

## Phase D — 포트폴리오 통합 체크리스트

- [ ] `yeonwoo-dev/web/content/projects/ask-wiki.mdx` — work 프로젝트와 같은 구조(개요→문제→해결 전략→기술 선택 이유→검증), category는 side(Products)
- [ ] 개발 기록(devlog) 후보: ①캐시는 왜 로컬/분산을 나눴나 ②인메모리 벡터 인덱스와 확장 경로 (→ `design-notes.md`에 초안 있음) ③B1 정합성 설계기 ④B2 평가 하네스
- [ ] 아키텍처 다이어그램 3중 구조 등록 (mdx + architecture.ts + Arch 컴포넌트)
- [ ] Products 등재 조건: 실제 상시 운영(또는 데모 가능한 배포) + 본인이 실사용
- [ ] 이력서 반영은 **측정 성과가 나온 뒤**: Skills(Spring AI·k6·Grafana 등) + Side Project 한 줄
- [ ] 정직성 규칙: 실측만, 측정 환경 명시, 재현 스크립트 커밋, AI 활용은 역할 구분해 표기

## 세션 시작 프롬프트 (복붙용)

> "ask-wiki 레포에서 Phase B1(증분 인덱싱·정합성) 이어서 작업할 거야.
> `docs/START-HERE.md` → `docs/ROADMAP.md` → `docs/DESIGN.md` 순으로 읽고 현재 상태를 파악해줘.
> 설계 선택지는 나와 논의하고, 핵심 구현은 내가 직접 한다. **START-HERE §7의 step-by-step 방식으로,
> 한 단계씩 목표·코드·확인 방법을 알려주면 내가 직접 타이핑한다.** 너는 재현 테스트 데이터와
> 보일러플레이트를 도와주고, 진척과 판단은 그때그때 문서에 기록해줘."
