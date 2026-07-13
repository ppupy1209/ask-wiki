# ROADMAP — 운영 수준 딥다이브 (Phase B·C)

> **원칙: 한 번에 한 딥다이브.** 순서는 **B1 → B2 → C2 → C1 → C4 → C3** (W만 병행 허용, 2026-07-11 C4 편입·순서 현행화).
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
| Claude Code 활용 전문가 (서브에이전트·MCP·훅) | **W** — 워크플로우 아티팩트화 (병행) + **C4** — ask-wiki MCP 서버 |

- **C1 벡터 DB 이행** (구 백로그 B6 승격): 기술 선택(Qdrant·pgvector·ES kNN 비교) → B1 동기화 패턴 이식 → **B2 하네스로 이행 전후 품질 동등성·성능 비교**. "붙여봤다"가 아니라 "이행하며 정합성을 지켰고 회귀 없음을 증명했다"가 목표.
    - **(2026-07-11 강의 접목 P1)** 후보 비교 시 **Elasticsearch는 강의 섹션 7·8 실습 코드로 검증**(학습 비용↓). 단 강의가 ES를 쓴다는 이유로 자동 채택 금지 — 비교표는 원칙대로 채운다. 설계 결정 2건 추가: ① 자체 `InMemoryVectorIndex` → Spring AI **`VectorStore` 표준 인터페이스** 채택 여부(직접 구현 유지 vs 표준 추상화 — "왜"를 design-notes에, D안 Kafka 서사와 같은 구조) ② Spring AI **ETL 파이프라인**(DocumentReader·TokenTextSplitter) vs 자체 Chunker+PDFBox 비교 — **B2-5 매트릭스 재실행**으로 검증.
- **C2 상용 LLM 프로바이더 스위치**: Spring AI 추상화로 Ollama ↔ Claude/GPT/Gemini 전환. 토큰·비용 Micrometer 지표(공고의 "토큰 최적화"), 타임아웃·세마포어·degraded 폴백(구 B4 흡수), B2로 프로바이더별 품질·비용 비교. 부수 효과: 저비용 운영 배포 가능(백로그 배포 과제 해소). B3(SSE first-token)도 여기서 결합 검토.
- **C4 ask-wiki MCP 서버 (✅ 완료 2026-07-13 — 스위트 39/39 그린, 순환 의존성 발견·`@Lazy` 절단, design-notes §6)**: 검색·질의를 **MCP tool(`search_wiki`·`ask_wiki`)로 노출** → Claude Code/Desktop이 위키를 직접 질의하는 데이터 소스가 됨. 서사 = "정합성(B1)·품질 검증(B2)된 검색을 표준 프로토콜로 AI 도구 생태계에 개방". 구현은 `spring-ai-starter-mcp-server-webmvc` — **Boot 3.3.0+ 지원·1.0.x 라인 포함 확인 완료(버전 상향 불필요, C2-1식 함정 없음 — 착수 시 1.0.9 BOM 포함 재확인만)**. 공고 "MCP" 키워드에 훅(W-1)에 이어 실제 제품으로 대응, W-3/W-4 글감과 교차. **순서 = C1 뒤·C3 앞**(1~2세션 경량 페이즈, 2026-07-11 연우님 확정). 강의 섹션 10 레퍼런스.
- **C3 에이전틱 RAG**: 단발 검색→답변을 넘어 검색 필요 판단·질문 재작성·다중 검색 루프. 프레임워크(Spring AI tool-calling vs LangChain4j)는 착수 시 비교·결정 — LangChain/LangGraph 키워드에는 "동일 개념을 자바 생태계로 구현 + 원리로 설명" 전략으로 대응. 개선은 반드시 B2 하네스로 증명.
    - **(2026-07-11 강의 접목 P2)** 구성요소 구체화: ① 프레임워크는 **Spring AI Tool Calling에 무게**(강의 섹션 9 레퍼런스, 자바 생태계 일관성 — 착수 시 최종 결정은 유지) ② "검색 필요 판단·라우팅"은 강의 섹션 6의 **CS 문의 자동 분류 패턴(Structured Output)** 응용으로 구현 ③ **ChatMemory 멀티턴** — 대화 맥락 기반 질문 재작성(follow-up 질문 처리), 웹 UI가 단일턴 Q&A→진짜 챗봇이 되는 제품 개선 포함. 검증은 B2 하네스 + **멀티턴 golden set 확장**(현행은 단일턴 50문항뿐).
- **강의 접목 메모 (2026-07-11)**: 인프런 ["실무에 바로 적용하는 Spring AI: 챗봇·RAG·MCP 도입하기"](https://www.inflearn.com/course/spring-ai-in-practic)(JSCODE 시니, 입문, 7h18m·75강 — Spring AI 1.0 GA·OpenAI+Ollama·Elasticsearch·MCP)를 로드맵에 매핑. **평가**: 섹션 2~5·7(LLM 기초·Spring AI 기본기·채팅 API·유사도 검색)은 이 프로젝트가 이미 초과 달성(측정·평가 하네스 보유)이라 반영 없음. 실질 접목 = **섹션 8→C1 보강(P1) · 섹션 6/9→C3 구체화(P2) · 섹션 10→C4 신설(P3)** — 3건 모두 2026-07-11 연우님 승인. 수강 전략: 해당 Phase 착수 직전에 해당 섹션만(예: C1 전 섹션 7·8, C4 전 섹션 10).
- **W Claude Code 워크플로우 아티팩트화 (병행)**: 이미 실천 중인 것을 레포에 보이게 만든다.
    - [x] W-1: `CLAUDE.md` + 커밋 전 금칙어 검사 훅 ✅ (2026-07-07) 프로젝트 `CLAUDE.md`(빌드·테스트·컨벤션·워크플로우) + **secret-scanner 방식** 커밋 가드 2겹: (1) git `.githooks/pre-commit`(레포 동봉·범용, `core.hooksPath .githooks`로 설치) (2) Claude Code `.claude/settings.json` PreToolUse 훅(AI 세션용, `.githooks/claude-commit-guard.sh`, fail-open). 스테이지된 **추가 줄만** `.githooks/denylist.txt`와 대조(기존 정당한 표현 재검사 안 함, denylist 파일 자기 제외). 4개 케이스 테스트 통과. 채용 공고 "Claude Code 훅" 직접 대응.
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

- [x] 3-1: V2 Flyway 마이그레이션 — `index_outbox` 테이블 (status 인덱스 포함) ✅ Codex 작성, Claude 검증
- [x] 3-2: `IndexOutboxEvent` 엔티티(`search` 패키지) + `IndexOutboxRepository`(`findByStatusOrderByIdAsc`) ✅
- [x] 3-3: `create()` 수정 — `vectorIndex.add()` 제거, 같은 트랜잭션에서 outbox INSERT. **`GhostIndexTest` 초록 전환 확인**(2026-07-07): 롤백 테스트 `outbox.count()==0`·`vectorIndex.size()==0` 통과, 해피패스 `recordsPendingOutboxEventsWhenCreateCommits`(1 doc/3 chunk/3 PENDING/index 0) 통과, 전체 스위트 그린. 유령 2건 → 0건. **삭제·relay는 다음 스텝.**
- [x] 3-4: relay — `@Scheduled` 폴링, PENDING 읽어 인덱스 반영 후 PROCESSED. 멱등. ✅ Codex 작성, Claude 검증(2026-07-07): `IndexOutboxRelayTest` 3개 통과 — `reflectsCommittedChunksAfterRelayRuns`(반영 후 index 3·PROCESSED 3), `isIdempotentOnRepeatedPolls`(재폴링 no-op·중복 0), `doesNotDuplicateWhenChunkAlreadyIndexed`(rebuild 후 재처리해도 dedup으로 index 3 유지). 전체 스위트 8개 그린. add→mark 순서, add() chunkId 멱등 적용.
    > **relay 설계 결정 (2026-07-07, 면접 Q3 멱등성 대응)**:
    > ① **순서 = 인덱스 add 먼저 → 그다음 PROCESSED 마킹** (at-least-once). 반대로 하면 "마킹 후 크래시 → 반영 누락(유실)". add를 먼저 하면 최악의 경우 재처리(중복 시도)뿐이라 멱등으로 흡수 가능.
    > ② **멱등성 2겹**: (a) 상태 필터로 PENDING만 처리, (b) 재처리·재시작 시 중복 방지를 위해 `InMemoryVectorIndex.add()`를 **chunkId 기준 멱등**으로 변경(이미 있으면 skip). 재시작 시 `rebuild()`가 전량 로드한 뒤 남은 PENDING을 relay가 재처리해도 dedup으로 안전 — 파생 데이터(E) 관점과 Outbox(C)가 만나는 지점.
    > ③ **스케줄링은 프로퍼티로 게이트**(`askwiki.outbox.scheduler-enabled`, 기본 on) → 테스트에선 off로 두고 `relay.processPendingEvents()`를 **수동 호출**해 결정적으로 검증(@Scheduled 백그라운드 실행이 단언을 흔들지 않게).
    > ④ 폴링 주기 프로퍼티(`askwiki.outbox.poll-interval-ms`, 기본 1000) → 3-7 "반영 지연 vs 폴링 주기" 측정 노브.
    > ⑤ 청크가 이미 삭제됐으면(findById 없음) add 없이 PROCESSED로 마킹(무한 재처리 방지).
- [x] 3-5: relay-kill 장애 주입 ✅ Codex 작성, Claude 검증(2026-07-07): `IndexOutboxRelayFailureTest` 2개 통과 — `recoversWithoutLossWhenRelayCrashesMidBatch`(크래시 후 3 PENDING 보존=유실 0, 재시도 시 index 3·중복 0·3 PROCESSED), `marksProcessedWhenChunkMissing`(obsolete 이벤트도 PROCESSED, 무한 재처리 방지). **전체 스위트 10개 그린.** (①②는 3-4에서 완료.)
    > **설계 (2026-07-07)**: 실제 JVM kill 대신 "마킹 전 크래시"를 결정적으로 시뮬레이션 — `@SpyBean InMemoryVectorIndex`에 카운터 기반 answer로 **2번째 `add()`에서 예외**를 던진다. `processPendingEvents()`는 `@Transactional`이라 예외 시 전체 롤백 → 이미 처리한 이벤트의 `markProcessed()`(dirty)도 커밋 안 됨.
    > - 테스트 A `recoversWithoutLossWhenRelayCrashesMidBatch`: create()로 3 PENDING → 크래시(2nd add throw)로 `processPendingEvents()` 예외 → **유실 0 검증**: 여전히 3 PENDING·0 PROCESSED(마킹 롤백) → 재시도(스텁이 이후엔 real) → index 3(크래시 전 부분 add가 **dedup**돼 4 아님)·0 PENDING·3 PROCESSED. = at-least-once + 멱등이 유실도 중복도 막음.
    > - 테스트 B `marksProcessedWhenChunkMissing`: 청크 삭제 후 PENDING 이벤트만 남은 상태(obsolete) → `processPendingEvents()`가 예외 없이 add 건너뛰고 PROCESSED 마킹(무한 재처리 방지, `ifPresent` 경로 검증).
    > - 신규 클래스 `IndexOutboxRelayFailureTest`(@SpyBean 격리 위해 별도).
- [x] 3-6: 삭제 경로 — outbox 통일 + 증분 제거 + AtomicReference 세대 스왑 ✅ Codex 작성, Claude 검증(2026-07-07): `InMemoryVectorIndex`를 `AtomicReference<List<Entry>>`+`updateAndGet`로 전환(add 멱등·`removeDocument` 증분), `delete()`는 `DOCUMENT_DELETED` 이벤트 기록(vectorIndex 의존성 제거), relay가 타입 분기. V3로 `chunk_id` nullable. `IndexOutboxRelayTest` +2(`reflectsDeletionAfterRelayRuns`·`removeDocumentOnlyAffectsTargetDocument`). **전체 스위트 12개 그린.** (설계·근거 design-notes §3 "삭제 경로")
    > **정정**: 기존 `rebuild()`는 이미 원자 교체(volatile)라 검색 다운타임은 원래 ~0. 진짜 개선은 delete() 동기 전체 재빌드 제거.
    > - V3 마이그레이션: `index_outbox.chunk_id`를 nullable로. `IndexOutboxEvent.EventType`에 `DOCUMENT_DELETED` 추가(엔티티 chunkId nullable화).
    > - `InMemoryVectorIndex`: 내부 `AtomicReference<List<Entry>>` + `updateAndGet`로 add/rebuild 전환, `removeDocument(Long documentId)`(해당 documentId 엔트리 제거, 멱등) 추가.
    > - `DocumentService.delete()`: `vectorIndex.rebuild()` 제거 → 같은 트랜잭션에서 `DOCUMENT_DELETED` 이벤트 기록.
    > - relay: 이벤트 타입 분기(CHUNK_ADDED→add, DOCUMENT_DELETED→removeDocument).
    > - 테스트: 삭제 반영(생성·relay로 index N → 삭제·relay로 0), 삭제 멱등(재폴링 no-op), 삭제 롤백(이벤트도 롤백).
- [x] 3-7: 측정 ✅ (2026-07-07, Claude 작성·실행 — Codex가 샌드박스 쓰기 권한에 막혀 측정 하네스는 직접 작성). `IndexReflectionLatencyTest`.
    > **측정 설계**: B1이 새로 도입한 양은 **relay 폴링 지연**뿐이므로, full e2e(임베딩 시간에 묻힘) 대신 이것만 격리 측정. Testcontainers + 실제 스케줄러(`scheduler-enabled=true`, `poll-interval-ms=200`) + 목 임베딩. 각 시행: create() 커밋→인덱스 반영까지 ms.
    > **방법론 함정(직접 발견·수정)**: 처음엔 "반영 직후 바로 다음 create()"라 커밋이 항상 폴링 직후에 정렬돼 지연이 늘 ~T로 나옴(평균 219.7ms ≈ T). 실제 업로드는 폴링 시계와 무관하게 도착하므로, create() 전 **랜덤 지터(0~T)**를 넣어 균등 분포로 만든 뒤 재측정. → 편향 제거. (측정 방법이 결과를 만든다는 실례 = 포트폴리오 글감.)
    > **실측(pollMs=200, 20회, 지터 적용)**: min 29 / **mean 126.7 / max 359** ms. max 359는 첫 시행 워밍업 아웃라이어(이후 ≤211). 평균이 이상값 T/2=100보다 약간 큰 건 `fixedDelay`가 "완료 후 200ms"라 실효 주기가 200ms+relay처리(~20-40ms)이기 때문. → **모델 검증: 평균≈실효T/2, 최대≈실효T**. 프로덕션 기본 T=1000ms이면 평균 ~500ms·최대 ~1000ms.
    > 트레이드오프: 작은 T=신선한 인덱스↔폴링 부하↑. 즉시성이 필요하면 이벤트 드리븐(하지만 단일 인스턴스엔 Kafka 과함 = D안 서사).

### 측정할 숫자 (before → after) — 실측 완료 (2026-07-07)

- 롤백 시나리오에서 유령 인덱스 엔트리: **2건 → 0건** (`GhostIndexTest`, Outbox 도입 전후).
- 문서 갱신 → 검색 반영 지연: pollMs=200에서 **평균 126.7ms / 최대 ~211ms**(워밍업 제외), 균등 분포 실측. 관계 = **평균≈실효T/2, 최대≈실효T** → 프로덕션 T=1000ms면 평균 ~500ms·최대 ~1000ms. (`IndexReflectionLatencyTest`)
- 삭제 시 검색 불가 시간: **원래도 ~0**(기존 `rebuild()`가 이미 원자 참조 교체라 다운타임 없음 — 정정). 진짜 개선은 **delete() 응답 지연: 동기 전체 재빌드(DB 전 청크 재읽기·재파싱) → 이벤트 1건 기록**. + AtomicReference로 동시 변경 안전성 확보.

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

### Step 세부 진행 (2026-07-07 결정: 사내 HR/총무 golden set + JUnit @Tag("eval") 러너)

> **역할**: golden set 데이터(문서·질문)는 재현 테스트 데이터라 **Claude가 초안 작성 → 연우님 검수**. 러너·측정 코드는 Codex 위임→Claude 검증. hit rate는 LLM 없이 결정적(CI 적합), 생성 지표는 LLM 필요(로컬).
> **핵심 설계**: golden set은 문서를 DB auto-increment id가 아니라 **논리적 slug**로 참조. 러너가 코퍼스를 업로드하며 slug→dbId 매핑을 잡고, hit rate는 "기대 slug의 dbId가 topK 검색결과에 포함됐나"로 판정 → id 흔들림과 무관하게 결정적.

- [x] B2-1: golden set 포맷·스키마 확정 ✅ (2026-07-07) 코퍼스 `src/test/resources/eval/corpus/*.md`(H1=제목, 파일명=slug) + `questions.json`(answerable/unanswerable 배열, expectedDocSlug·expectedAnswer·difficulty). 연우님 포맷 승인.
- [x] B2-2: golden set 데이터 작성 ✅ (2026-07-07, Claude 초안) — HR/총무 **8문서**(vacation·attendance·salary·expense·welfare·security·onboarding·equipment) + **질문 50개**(answerable 30: easy/medium/hard 섞음, unanswerable 20: 도메인 인접 함정). 선정 기준은 `eval/README.md`. **⚠️ 연우님 사실관계 검수 필요**(정답이 문서와 일치하는지).
- [x] B2-3: hit rate@K 러너 ✅ (2026-07-07, Codex 작성 배경 태스크 착지·Claude 검증) `eval/HitRateEvalTest.java`(`@Tag("eval")`, 기본 `test`에서 제외 — build.gradle `excludeTags`/`evalTest` 태스크). 실제 Ollama 임베딩 필요(채팅 LLM 없음, 결정적). **기준선 실측**: `@1=36.7% @2=56.7% @4=93.3% @8=100.0%` (난이도별 @4: easy 88.9%·medium 92.3%·hard 100%). 해석: @1→@4 급등(청크 경쟁 때문에 top-1은 낮지만 top-4에 기대문서 거의 포함) → topK=4 기본값이 이 코퍼스에선 타당, @8은 +6.7%뿐. 난이도 역전은 표본 작아(30문항, @4 오답 2건) 노이즈.
- [x] B2-4: 환각률·오거부율 러너 ✅ (2026-07-07, Codex 작성·Claude 실행) `HallucinationEvalTest`(@Tag("eval"), 실제 llama3.2). **기준선 실측: 환각률 65%(20중 13 지어냄)·오거부율 0%(30중 0).** → llama3.2:3b는 "너무 대담" — 없는 것도 답하고(환각↑) 있는 건 절대 안 놓침(오거부 0). **핵심 발견: Phase A의 1회 수동 확인("환각 억제 OK")은 거짓 안심이었다 — 20문항 체계 측정하니 65% 환각. 이게 golden set이 필요한 이유.** 개선 레버 = 프롬프트 강화(환각률 ↓ 재측정, 아래 측정할 숫자).
    > **설계 (2026-07-07)**:
    > - **환각률**(핵심): 답 없는 20문항에 `RagService.answer(q,4)` → `Answered.answer()`가 "모르겠습니다"를 **안** 담으면 환각(지어냄). 환각률=지어낸/20.
    > - **오거부율(false refusal)**: 답 있는 30문항에 답변이 잘못 "모르겠습니다"면 오거부. 오거부율=오거부/30. (환각률과 짝 — 프롬프트가 소심하면 오거부↑, 대담하면 환각↑. 이 트레이드오프가 프롬프트 튜닝의 핵심.)
    > - **판정 = 문자열 매칭**("모르겠습니다" 포함 여부). 우리 프롬프트가 no-context 시 정확히 그 문구를 내도록 설계돼 있어 잘 맞고, **결정적·재현 가능**.
    > - **대안(문서에 명시, 채택 안 함)**: ① **LLM-as-judge** — 다른 LLM에게 "이 답변이 모른다고 했나/정답인가"를 물어 판정. 표현 변형("정보가 없습니다")에 유연하지만 더 느리고 비결정적이며 판정자도 틀릴 수 있음. ② **답변 정답성(answer correctness)** — 답변을 `expectedAnswer`와 대조(정답을 실제로 맞혔나). 문자열로는 취약(표현 차이)해 LLM-judge가 필요 → 후속 과제. ③ **인용 정확도** — sources가 정답 문서를 가리키는 비율인데, sources=topK 검색결과라 **hit rate와 동일 신호**라서 별도 측정 생략(참고용으로만 답변 도달 건에서 부수 집계 가능).
    > - 러너: `HallucinationEvalTest`(@Tag("eval")). 출력 `[GEN-QUALITY] hallucination=..% falseRefusal=..%`. 비결정적이라 값은 실행마다 다름(그래서 CI 제외, 로컬 주기).
- [x] B2-5: 실험 매트릭스 ✅ (2026-07-07) `Chunker` 설정화 + `ChunkSizeMatrixEvalTest`. 결정: **청크 500·topK 4 유지**(실험이 검증). 200자는 파편화로 하락, 400~800은 동일(짧은 문서 한계).
    > **설계 (2026-07-07)**: 선행 = `Chunker`의 하드코딩 `TARGET_CHARS=500`/`OVERLAP_CHARS=50`을 설정값(`askwiki.chunk.target-chars`/`overlap-chars`, 기본 500/50)으로 추출 → 크기 가변(운영 동작 불변). 실험 = 자체 완결형 `@Tag("eval")` 테스트(`ChunkSizeMatrixEvalTest`): 크기별로 `new Chunker(크기)`로 8문서 청킹 → 각 청크 임베딩(실제 Ollama) → 질문마다 `SearchMath.cosineSimilarity`로 top 8 → hit@{2,4,8}. HitRateEvalTest와 동일 채점, 청크 크기만 스윕. Codex 작성·Claude 검증.
    > **실측 매트릭스 (2026-07-07)**:
    >
    > | chunk size | chunks | @2 | @4 | @8 |
    > |---|---|---|---|---|
    > | 200 | 12 | 50.0% | 73.3% | 96.7% |
    > | 400 | 8 | 56.7% | **93.3%** | 100% |
    > | 800 | 8 | 56.7% | **93.3%** | 100% |
    >
    > **발견**: 200자는 @4가 73.3%로 하락(문서가 12청크로 파편화 → 내용 흩어짐), 400·800은 동일(93.3%). 문서가 짧아(각 300~450자) 400 이상에선 **문서 1개=청크 1개**로 유지돼 크기 차이가 안 나타남. → **현재 기본 500자는 좋은 구간(400~800 plateau)에 있고, ~200자 이하로 내리면 손해**.
    > **정직한 한계**: 짧은 문서라 400/500/800을 구분 못 함(전부 1문서=1청크). 청크 크기를 더 날카롭게 튜닝하려면 **긴 문서**가 필요 — 후속 개선 후보. topK는 HitRate 실측대로 @4가 스윗스팟(@2→@4 급등, @4→@8 미미).
    > **기본값 결정**: 청크 500·topK 4 **유지**(실험이 현 기본값을 검증). 근거는 위 매트릭스 + design-notes §3.

### 측정할 숫자

- hit rate@4: 기준선 **93.3%**(청크 500·topK 4·8문서 코퍼스, 2026-07-07 실측). @1=36.7%/@8=100%. **B2-5 매트릭스 결과: 400~800에서 93.3% plateau, 200은 73.3%로 하락 → 현 기본값 검증(유지)**. 실험이 청크 크기로 개선 여지를 못 만든 게 결론(짧은 문서 한계).
- 환각률: 프롬프트 4종 실측 — v1 65%/0%, v2 20%/27%, v3 70%/3.3%, v4 60%/3.3%(환각/오거부). **프롬프트는 트레이드오프 곡선 위 이동만**. 유사도 임계값 가설도 **측정으로 기각**(answerable/unanswerable top-score 분포 완전 겹침: 0.727~0.871 vs 0.737~0.831). **결론: 로컬 소형 모델(llama3.2:3b + nomic-embed-text)의 환각 바닥은 프롬프트·임계값으로 못 넘음 → 진짜 레버는 더 강한 모델(C2)**. 상세·프롬프트 전문: design-notes §3 + b2-prompt-experiments.md.

### 학습 확인 질문 (면접 대비)

1. 검색 품질과 생성 품질을 왜 분리해서 측정하나?
2. 청크가 크면/작으면 hit rate와 답변 품질에 각각 어떤 영향?
3. 평가를 CI에 넣을 때 LLM 호출 지표는 왜 빼거나 분리하나? (비용·비결정성)
4. hit rate가 높은데 답변이 틀린다면 어디를 의심하나?

---

## Phase C2 — 상용 LLM 프로바이더 스위치 (딥다이브 ④, 계획 2026-07-10)

> **정량적 동기 (B2에서 옴)**: 로컬 소형 모델(llama3.2:3b + nomic-embed-text)의 환각 바닥(65%/60%)은 프롬프트·유사도 임계값으로 못 넘었다(design-notes §3, 원인 (a) 임베딩이 인접-부재 항목 미분리 (b) 3B 모델의 "구체 답이 실제로 있나" 판단 약함). C2의 서사 = **B2 하네스로 "더 강한 상용 모델이 그 바닥을 실제로 깬다"를 증명**한다. "API 붙였다"가 아니라 "측정으로 개선을 증명했다"가 목표.

### 확정 결정 (2026-07-10, 연우님)

- **프로바이더: 세 개(Claude / GPT / Gemini) 모두 스위치 가능하게** 설계 — 공고의 "Claude/GPT/Gemini + 토큰 최적화" 직접 대응. 우선 1개 붙이고 확장. ⚠️ 실제 비교 측정은 **API 키가 있는 프로바이더**로만 가능(키 없는 건 스위치 골격까지).
- **측정 순서 확정 (2026-07-10)**: ① **Gemini 무료(1순위)** — Google AI Studio API 키(구독과 별개, 카드 불필요). Spring AI `spring-ai-starter-model-google-genai`가 Gemini Developer API(무료 티어) 지원(`spring.ai.google.genai.api-key`만; `project-id`/`location` 넣으면 Vertex 과금 모드로 전환되니 금지). ⚠️ BOM 1.0.9 포함 여부는 C2-1 첫 확인. ② **불만족 시 Claude Sonnet 5로 escalation** — thinking **disabled**(RAG는 사고 불필요, 안 끄면 사고 토큰이 출력 단가로 붙어 비용·지연↑). 비용: full eval 1회(~50Q) ≈ Sonnet 5 인트로 $0.28/정가 $0.41, Haiku 4.5 $0.14 — 프롬프트 튜닝 10회도 몇 달러. **왜 별도 과금**: 구독(Claude Max·ChatGPT·Gemini Advanced)은 챗 제품용이라 개발자 API 키로 못 씀 → Gemini 무료 티어만 $0 실측 경로.
- **C2-1 발견·결정 (2026-07-10)**: `spring-ai-starter-model-google-genai`는 **Spring AI 1.0.9에 없음**(1.1.0-M1부터, GA 1.1.0). 게다가 1.1.x는 **Spring Boot 3.5.x 기준**인데 레포는 **3.3.5** → BOM 상향은 그린 스위트·Testcontainers 핀에 리스크라 비권장. **채택 = A안(OpenAI 호환 엔드포인트)**: 1.0.9에 있는 `spring-ai-starter-model-openai`를 **Gemini OpenAI-compat 엔드포인트**(`https://generativelanguage.googleapis.com/v1beta/openai/...`)로 base-url 지정 + 무료 Gemini 키. 버전 상향 0·공식 스타터·무료·최소 코드. 트레이드오프: Gemini가 "openai" 스타터 뒤에 있어, 나중에 진짜 GPT 추가 시 설정 분리 필요. (대안 B=커스텀 Gemini 어댑터, C=Boot 3.5 상향은 보류.)
- **범위: 챗 LLM만 교체(1차)**. 임베딩은 `nomic-embed-text` 유지 → 쿼리당 추가 비용 0, 캐시 구조 불변. **부수 효과 = 실험 통제**: 임베딩을 고정하므로 환각이 내려가면 원인을 LLM 판단(b)으로 귀속할 수 있음(임베딩(a)과 분리 진단). 임베딩 교체는 후속 — 유료화 시 임베딩 캐시 Redis 이전은 design-notes §1이 이미 예고.
- **B3(SSE) 미포함** — C2는 스위치·관측·장애격리·품질비교에 집중. B3는 후속.

### 현재 코드 상태 (착수 전 실측, 2026-07-10)

- Spring AI 인터페이스가 이미 추상화 지점: `RagService`가 `ChatModel`(인터페이스) 주입, `EmbeddingClient`가 `EmbeddingModel` 래핑. `build.gradle`엔 `spring-ai-starter-model-ollama`만 → 오토컨픽이 `ChatModel` 1개 빈만 생성.
- ⚠️ `RagService`는 `chatModel.call(String)` — usage(토큰) 메타를 버린다. 토큰 관측하려면 `call(Prompt)` → `ChatResponse.getMetadata().getUsage()`로 전환 필요.
- 장애 격리 전무: `try/catch` → `RagResult.LlmError`만. 타임아웃·세마포어·폴백은 net-new(구 B4).
- Micrometer→Prometheus→Grafana는 이미 배선됨. `QueryCache`의 카운터 등록 패턴을 토큰·비용 지표에 그대로 복제 가능.

### 설계 (제안 — 착수 시 확정)

- **프로바이더 선택**: 프로퍼티 `askwiki.llm.provider`(ollama|anthropic|openai|gemini, 기본 ollama)로 활성 `ChatModel` 선택. 여러 스타터를 클래스패스에 두고 `@ConditionalOnProperty`/팩토리로 하나를 primary 주입. **같은 jar를 프로바이더만 바꿔 B2 하네스로 각각 돌릴 수 있게** — 비교 측정이 목적이므로 런타임 스위치가 핵심 이음새.
- **토큰·비용 관측**: `QueryCache` 카운터 패턴 재사용 → `llm.tokens{provider,type=input|output}`, `llm.calls{provider}`, `llm.latency`(타이머), 프로바이더별 단가로 추정 비용. Grafana 패널 추가.
- **장애 격리 (구 B4)**: LLM 호출 타임아웃 + 동시성 세마포어(상한) + degraded 폴백(`RagResult` 새 케이스 or `LlmError` 확장). 부하 중 타임아웃/kill 주입 테스트로 폴백 검증.
- **시크릿**: API 키는 커밋 금지 — `.env` + W-1 denylist(`.githooks/denylist.txt`)에 키 패턴 추가(secret-scanner 연결, W와 교차).

### 과제 (Step 세부 — 착수 시 step-by-step)

- [x] C2-1 ✅ (2026-07-10): `spring-ai-starter-model-openai` 추가 + `askwiki.llm.provider`(ollama|gemini) 스위치. **선택 방식 = `LlmProviderEnvironmentPostProcessor`**(EPP)가 provider→`spring.ai.model.chat` 매핑(ollama→ollama, gemini→openai)을 최우선 프로퍼티로 주입 → 선택된 챗 모델만 오토컨픽(@Primary 불필요 + 미선택 프로바이더 키 요구 없음). 임베딩은 `spring.ai.model.embedding=ollama`로 nomic 고정(함정 A). application.yml에 Gemini OpenAI-compat 설정(base-url=`generativelanguage.googleapis.com` + completions-path=`/v1beta/openai/chat/completions`, model=`gemini-2.5-flash`, api-key=`${GOOGLE_GENAI_API_KEY:}`; project-id/location 없음). **전체 13 테스트 그린**(provider=ollama·키 없이). 코덱스가 Windows에서 겉돌아(모델버전→hang→wedged broker→PowerShell patch thrashing) Claude가 직접 마무리.
    > **함정 발견·기록**: `spring-ai-starter-model-openai`는 chat·embedding뿐 아니라 **audio(speech/transcription)·image·moderation 모델까지 오토컨픽**하고, 이들이 빈 api-key로 생성되며 `IllegalArgumentException: OpenAI API key must be set`로 **컨텍스트 전체를 무너뜨림**. → `spring.ai.model.{image,audio.speech,audio.transcription,moderation}=none`으로 비활성화해야 함정 B(키 없이 부팅) 성립. chat=ollama만으로는 부족.
- [x] C2-2 ✅ (2026-07-10): `RagService`가 `chatModel.call(prompt)`(String) → `chatModel.call(new Prompt(prompt))` → `ChatResponse`로 전환. 답변은 `response.getResult().getOutput().getText()`, 토큰 usage는 `response.getMetadata().getUsage()`(prompt/completion/total)로 확보해 현재는 DEBUG 로깅(`logTokenUsage`). RagResult.Answered DTO는 불변(AskController/QueryCache 무영향). 컴파일·회귀 13 그린. **라이브 usage 관찰은 Ollama 필요**(임베딩) → C2-3/C2-5에서 확인. 기능상 call(String)과 동치 + usage 로깅만 추가라 안전.
- [x] C2-3 ✅ (2026-07-10): `LlmMetrics` 컴포넌트(QueryCache 카운터 패턴)가 `llm.calls{provider}`·`llm.tokens{provider,type=input|output}`·`llm.cost.usd{provider}`·`llm.latency{provider}`(타이머)를 기록. RagService가 LLM 호출을 `System.nanoTime()` 타이머로 감싸 `llmMetrics.record(response, latency)` 호출(C2-2의 로깅을 대체). 비용은 provider별 단가맵(ollama·gemini 무료=0, Sonnet 등 유료는 추가). **결정적 단위테스트 2개**(`LlmMetricsTest`, SimpleMeterRegistry+Mockito — Ollama 불필요) → **전체 15 그린**. /actuator/prometheus 노출. **Grafana 패널 4개 추가**(`rag-observability.json`: 토큰·지연 p95/p99·비용·호출, provider별; `management`에 `llm.latency` 히스토그램 켬) — JSON 유효 검증. 남음(실행 시): 스택+Ollama 띄워 패널 렌더·실지표 관찰(C2-5와 함께).
- [x] C2-4 ✅ (2026-07-10): LLM 호출을 `LlmCallGuard`로 격리 — **동시성 세마포어**(`max-concurrent`, 획득 실패 시 BUSY)·**호출 타임아웃**(`call-timeout-ms`, 초과 시 TIMEOUT, 가상스레드 executor+future.cancel). 실패는 `LlmUnavailableException` → RagService가 **`RagResult.Degraded`**(검색된 근거 sources는 그대로 제공)로 폴백, AskController가 200 응답. `llm.degraded{provider,reason}` 지표. **실제 LLM 오류는 원인 그대로 전파해 LlmError 유지**(폴백과 구분). **장애 주입 단위테스트 4개**(`LlmCallGuardTest`: 타임아웃·세마포어소진(latch)·정상·실제오류전파 — Ollama 불필요) → **전체 19 그린**. 설정 `askwiki.llm.{max-concurrent:8,call-timeout-ms:20000,acquire-timeout-ms:2000}`. 구 B4 흡수.
- [x] C2-5 ✅ (2026-07-12): B2 하네스로 프로바이더별 비교 — 환각률·오거부율·hit·토큰·비용·지연. Ollama vs 상용. **환각 바닥 돌파 증명 완료**. design-notes §4 표.
    - **1차 진행 (2026-07-12, 맥)**: ① 하네스 보강 — `[LLM-USAGE]` 요약(호출·degraded·토큰·비용·지연, provider 태그)·pacing 노브(`askwiki.eval.pacing-ms`)·evalTest 캐시 무효화(`outputs.upToDateWhen false`)·gradlew exec bit 복원. ② **Ollama 재기준선(같은 날·같은 머신 비교용): 환각 75.0%·오거부 10.0%**, 토큰 in 50,885/out 3,190, 지연 mean 8.8s/max 44.3s — B2(60%/3.3%)와의 차이는 실행 간 요동, 구간(60~75%)으로 해석. ③ **Gemini 2.5 무료 경로 실측으로 닫힘**: 2.5-flash **RPM 5·RPD 20**(50문항 러너 불가), 2.5-flash-lite 신규 사용자 차단, Spring AI는 429 비재시도. 3.1-flash-lite·3.5-flash 프로브 OK(`reasoning_effort:none` 정상). 실행 조건: 모델 warm-up + `ASKWIKI_LLM_CALL_TIMEOUT_MS=120000`(콜드 로드가 C2-4 가드 20s에 걸림) + Gemini `ASKWIKI_EVAL_PACING_MS=13000`. 함정 상세 design-notes §4.
    - **✅ 완료 (2026-07-12)**: 계정 한도표 확인 결과 3.5-flash도 무료 RPD 20 → **gemini-3.1-flash-lite(RPM 15·RPD 500)가 유일한 무료 완주 경로**로 확정·측정. **환각 0.0%(0/20)·오거부 6.7%(2/30)** vs 같은 날 Ollama 기준선 75.0%/10.0% — **바닥 돌파 + 두 지표 동시 개선**(B2 "진짜 레버는 모델" 진단 증명, 프롬프트와 달리 트레이드오프 곡선 자체를 밀었음). 지연 mean 637ms(vs 8,823ms CPU 추론), 출력 토큰 658(vs 3,190 — 1/5), 비용 $0. hit rate는 임베딩(nomic) 고정이라 프로바이더 무관(93.3% 유지). application.yml 기본 Gemini 모델 `gemini-3.1-flash-lite` 확정(회귀 19 그린). 비교표·해석·정직한 각주는 design-notes §4.
- [ ] (선택) 저비용 단일 인스턴스 배포 — 상용 API로 Ollama 컨테이너 없이 경량 배포(백로그 배포 과제 해소).

### 측정할 숫자 (목표)

- 환각률/오거부율: ✅ 실측 — 같은 날 기준선 Ollama **75.0%/10.0%** → Gemini 3.1 Flash Lite **0.0%/6.7%** (바닥 돌파 + 동시 개선, 2026-07-12).
- 프로바이더별 토큰(입력/출력)·추정 비용·응답 지연 비교표: ✅ design-notes §4 (지연 637ms vs 8,823ms(CPU), 출력 토큰 1/5, 비용 $0/$0).
- 장애 주입: ✅ C2-4 단위테스트 4개(타임아웃·세마포어·degraded·오류 전파) + C2-5 실전에서 가드·degraded 경로 실동작 확인(콜드 로드 함정).

### 학습 확인 질문 (면접 대비)

1. Spring AI로 프로바이더를 어떻게 추상화했나? 런타임 스위치의 핵심 이음새는 어디인가?
2. 토큰·비용을 왜 프로바이더별로 관측하나? usage는 어디서 얻나(`call(String)` vs `call(Prompt)`)?
3. LLM 장애(타임아웃·과부하) 시 degraded 폴백을 왜 두나? 세마포어는 정확히 무엇을 막나?
4. 상용 모델이 환각 바닥을 깼다면 그게 임베딩 때문인가 LLM 때문인가? (범위=챗만 교체가 이 분리 진단을 가능케 함)

---

## Phase C1 — 벡터 DB 이행 (딥다이브 ③, ✅ 완료 2026-07-12)

> **목표**: "붙여봤다"가 아니라 **"이행하며 정합성(B1 패턴)을 지켰고, 회귀 없음(B2 하네스)을 증명했다"**. 구 백로그 B6 승격, 채용 공고 "벡터 DB 기반 RAG" 직접 대응. 강의 접목 P1(2026-07-11) 포함: ES 후보는 강의 섹션 7·8 실습으로 검증, Spring AI `VectorStore` 인터페이스·ETL 비교 결정.
>
> **✅ 완료 요약 (2026-07-12)**: ES kNN 이행 완료 — 포트 추출→ES 어댑터→B1 정합성 4종 ES 재증명(Step 2), 측정 5종(Step 3). **핵심 수치**: hit rate 회귀 없음(93.3 vs 90.0@4, 차이는 부동소수 경로), 20k 지연 역전(인메모리 22.8ms→ES 12.8ms), recall dial(0.83@nc100), 반영 지연 refresh 계층(127→879ms), 기동 재빌드 0. 벤치가 프로덕션 결함(`rebuild()` 무제한 벌크 OOM) 발견·수정. 구현은 Codex 위임·Claude 검증, 측정은 Claude 직접. 상세 design-notes §5.

### 문제 (착수 전 실측·계산, 2026-07-12)

- **O(N) 브루트포스의 스케일 곡선**: `InMemoryVectorIndex.search()`는 전 엔트리 내적(nomic 768차원, ≈3KB/벡터). 20k 청크 60MB·25ms는 여유지만 **검색이 데이터에 선형** — 1M 청크면 힙 ~3GB+·검색 ~1.2s+(선형 외삽). 시작 시 `rebuild()`가 전 청크 DB 로드 → 스케일 시 기동 지연.
- **멀티 인스턴스 정합성 결함(착수 분석에서 발견)**: 인스턴스마다 인덱스 사본을 들고 있는데, outbox relay가 이벤트를 PROCESSED로 마킹하면 **다른 인스턴스의 relay는 그 이벤트를 영영 못 본다** → 마킹한 인스턴스만 반영되고 나머지는 다음 재시작(rebuild)까지 어긋남. **현 구조는 단일 인스턴스 전제.** study ①(VT)로 처리량 2.3배를 증명해 놓고 스케일아웃하면 검색 정합성이 깨지는 모순 — B1의 "파생 데이터를 인메모리에 유지(공유 저장소는 C1로 이월)" 결정이 남긴 숙제.
- **트레이드오프(정직)**: 외부 저장소로 가면 공유 인덱스(스케일아웃)·ANN(HNSW, 로그성)·영속성(rebuild 불필요)을 얻지만, 네트워크 홉(20k 소규모에선 25ms보다 느려질 수 있음)과 **ANN 근사의 재현율 트레이드오프**가 생긴다 → 그래서 B2 hit rate 동등성 검증이 필수다.

### 설계 선택지 (Step 1에서 결정)

| 후보 | 장점 | 단점 | 판단 포인트 |
|---|---|---|---|
| A. 유지(인메모리) | 극저지연 25ms·운영 0 | 위 한계 그대로 | 기준선. "왜 지금까지 인메모리였나"는 design-notes §2가 이미 답함 |
| B. **Elasticsearch kNN** | 강의 섹션 8 실습 자료(학습 비용↓)·**하이브리드 검색(백로그 B5, BM25+벡터 RRF)이 같은 엔진에서 자연 확장**·업계 채택 신호 넓음 | 컨테이너 무거움(JVM 힙 ~1GB, 로컬 compose 스택 부담)·벡터 전용 아님 | B5 시너지를 얼마나 쳐줄 것인가 |
| C. Qdrant | 벡터 전용(Rust, 경량 수십MB)·필터링·페이로드 깔끔 | 강의 자료 없음·하이브리드는 별도·채용 신호 좁음 | "가벼운 전용 DB" 서사 |
| D. pgvector | RDB 하나로 벡터+메타 조인 | **이 레포는 MySQL** — Postgres 추가 시 DB 2개 운영 or 전면 이행(범위 폭발) | 사실상 고비용 → "왜 안 썼나" 기록용 |
| E. Redis+RediSearch | 기존 redis 컨테이너 재활용(이미지만 redis-stack으로) | §1 논지("캐시와 벡터 검색은 관심사가 다르다")와 충돌 — 겸용 시 장애 도메인 결합 | "왜 안 썼나" 기록용 |

- **스타터 검증 완료(2026-07-12, Maven Central POM 직접 확인)**: `spring-ai-starter-vector-store-{elasticsearch,qdrant,redis,pgvector}` 모두 **1.0.9 존재** — C2-1식 버전 함정 없음.
- **인터페이스 결정(강의 접목 P1-①)**: (i) Spring AI `VectorStore`를 도메인에 직접 주입 vs (ii) **자체 포트 `VectorIndex` 추출 + 어댑터**(내부에서 VectorStore든 네이티브 클라이언트든). (ii) 권장 근거: 이행 기간 두 구현 병행(벤치 mode 스위치·B2 동등성 A/B), 기존 테스트(Ghost/Relay)를 포트 기준으로 재사용, 우리 정합성 계약(chunkId 멱등 add·documentId 삭제)을 시그니처로 명시. `VectorStore`의 Document(String id+metadata) 모델은 우리 도메인(chunkId/documentId/seq + 본문 DB 하이드레이션)과 어긋남 — 표준 추상화를 도메인에 안 들인 "왜" 자체가 글감(D안 Kafka 서사 구조).

### 과제 (Step — 결정·핵심 구현은 연우님 직접, step-by-step)

- [x] Step 1 ✅ (2026-07-12): **ES kNN + 자체 포트(`VectorIndex`) 어댑터** 결정(연우님) — 채택·기각 근거 design-notes §5
- [x] Step 2 ✅ (2026-07-12): 포트 추출 → ES 인프라 → 어댑터 → B1 패턴 재증명, 전부 Codex 작성·Claude 검증. 2-1 `VectorIndex` 포트(동작 불변, `635431b`) · 2-2 ES compose 8.17.4+네이티브 클라이언트+스모크(`a7694e1`) · 2-3 `EsVectorIndex`(_id=chunkId 멱등·kNN·점수 역변환 2s−1·refresh 프로퍼티)+`askwiki.vector-index.impl` 스위치+계약 테스트 4(`89e5a10`) · 2-4 **B1 정합성 불변식 4종(유령 0·relay 반영+멱등·크래시 무유실·삭제 통합)을 ES 구현으로 재증명**(`66d9908`). 검증 중 Claude 수정 2건: 괄호 누락 컴파일 에러(2-3), **목 벡터 2차원→768차원**(ES가 dims 강제 — InMemory엔 없는 제약이라 B1 픽스처 복사 시 걸리는 함정, 2-4). 전체 스위트 11클래스 그린.
- [x] Step 3 ✅ (2026-07-12): 이행 검증·측정 5종 완료 — 3-① hit rate 동등성(93.3 vs 90.0@4, `48e0838`) · 3-② 20k 스케일 벤치(지연 22.8→12.8ms·recall 0.83@nc100·dial·rebuild 배치 수정 `26e849c`) · 3-③ 반영 지연(ES refresh 계층 127→879ms `7cba2cc`) · 3-④ 기동 시간(ES 재시작 재빌드 0 vs 인메모리 6.3s) · 3-⑤ ETL 비교(Chunker=TokenTextSplitter, 짧은 문서 수렴 → Chunker 유지). 상세 design-notes §5.
- [ ] (강의 접목 P1-②) Spring AI ETL vs Chunker ✅ 3-⑤에 흡수 — 짧은 문서라 수렴, Chunker 유지 결정.
- [ ] (강의 접목 P1-②) Spring AI ETL(DocumentReader·TokenTextSplitter) vs 자체 Chunker+PDFBox — B2-5 매트릭스 재실행으로 비교, 채택은 결과로

### 측정할 숫자 (목표)

- hit rate@4: ✅ 실측(2026-07-12) — memory **93.3%**(B2와 완전 일치, 결정적) vs ES **90.0%**(@1·@2·@8은 완전 동일, @4만 1문항 4↔5위 스왑). **근사 손실 아님**(num_candidates≥N=완전탐색) — 부동소수 코사인 경로 차이로 인한 근접 순위 스왑. 해석·조건 design-notes §5. ⚠️ 20k 벤치(num_candidates<N)에서 진짜 ANN 구간 재측정 필요.
- 검색 지연: ✅ 실측(2026-07-12, 20k·가우시안 랜덤) — 인메모리 avg **22.76ms** vs ES kNN avg **12.84ms**(ES 1.8배 빠름, 네트워크 홉에도 불구 — 교차점이 이미 20k 이전). 단 ES 우위는 기본 nc=100(recall 0.83) 동작점 기준. recall dial: nc 4→0.386 … 200→0.879. **부수 발견**: `rebuild()` 무제한 단일 벌크가 20k에서 OOM(가우시안 float 직렬화 200MB+) → `BULK_BATCH_SIZE=500` 배치로 수정(벤치가 프로덕션 결함 발견). 상세·해석 design-notes §5.
- 반영 지연: outbox→외부 인덱스 반영 (B1 실측 평균 126.7ms 대비)
- 기동: rebuild 전량 로드 제거 효과

### 학습 확인 질문 (면접 대비)

1. 인메모리 인덱스는 정확히 언제 한계에 부딪히나? (힙·기동 시간·멀티 인스턴스 정합성 — 각각 숫자로)
2. 브루트포스 코사인과 HNSW(ANN)의 차이는? 근사 검색의 재현율 손실을 무엇으로 검증했나?
3. Spring AI VectorStore 표준 인터페이스를 왜 도메인에 직접 들이지 않았나(또는 들였나)?
4. 이행 중·이행 후 정합성(B1 outbox 패턴)은 어떻게 유지되나? 멀티 인스턴스에서 인메모리 인덱스가 왜 깨지나?

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
