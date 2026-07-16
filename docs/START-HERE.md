# 여기서 시작하세요 (이어서 하기 가이드)

> 이 파일은 **사람도, AI(Claude/Codex)도** 이 프로젝트를 처음부터/중간부터 이어갈 수 있게 하는 안내서입니다.
> 다른 PC나 새 대화에서 시작할 땐 이 파일 → `README.md` → `docs/DESIGN.md` 순으로 읽으세요.

---

## 1. 이 프로젝트가 뭔가요? (한 문장)

**"사내 문서(회사 규정·매뉴얼 등)를 올려두면, 직원들의 자연어 질문에 그 문서를 근거로 답해주는 위키 챗봇(Ask Wiki)"** 입니다.
이런 방식을 **RAG**(Retrieval-Augmented Generation, 검색해서 답을 생성)라고 부릅니다. `http://localhost:8080` 웹 UI에서 업로드와 질문을 바로 쓸 수 있습니다.

왜 만드나: "그냥 Java 개발자"가 아니라 **"현대적이고 확장 가능한 시스템 + AI + 성능"을 다룰 줄 안다**는 걸
면접관이 직접 실행해보며 확인할 수 있는 포트폴리오를 만들기 위해서입니다.

## 2. 이 하나의 프로젝트로 보여주는 4가지 (study)

| # | 무엇을 | 쉽게 말하면 |
|---|---|---|
| ① | Virtual Threads | Java 21 신기능으로 "동시 접속자를 싸게 많이 처리"하는 걸 측정으로 증명 |
| ② | Spring AI + RAG | Java로 AI(로컬 LLM)를 붙여 문서 기반 답변을 만드는 것 |
| ③ | 검색 최적화 | 문서가 많아져도 "비슷한 문서 찾기"를 빠르게 |
| ④ | 캐싱 + 관측 | 같은 질문은 기억해서 빨리 답하고, 성능을 그래프로 기록 |

## 3. 진행 로그 (Phase별) - 이어가는 기준점 ⭐

전체 흐름: **Phase 1**(실행 확인) → **2**(RAG 구현) → **3**(Virtual Threads 측정) → **4**(숫자 정리) → **5**(포트폴리오 통합)

- ✅ **Phase 0 - 골격**: Claude+Codex가 전체 스캐폴딩. Docker 빌드·기동·Flyway·health 검증 완료.
- ✅ **Phase 1 - 실행 확인**: `docker compose up` → 문서 업로드/조회 동작 확인. (문서 올리면 Ollama가 임베딩해 MySQL 저장)
- ✅ **Phase 2 - RAG 구현 (study ②)**: `rag/RagService.answer()` 완성. 질문→임베딩→검색→프롬프트→LLM→답변+근거. `/api/ask`가 근거 기반 답변 반환. 핵심 학습: **프롬프트 한 줄이 답을 좌우(환각 억제)**.
- ✅ **Phase 3 - Virtual Threads 측정 (study ①)**: **실무 오케스트레이션 엔드포인트**(`/api/bench/orchestrate`: 인증→DB→보강→감사, 다운스트림 3회+DB)로 고도화. 결과 **처리량 538→1,257 req/s(2.3배), 평균 895→382ms, 부하중 JVM 스레드 OFF~350/ON~220**(직접 실측). 스크립트 `bench/orchestrate-load.js`(단순 비교용 io/downstream도 있음). 토글 `VTHREADS=true docker compose up -d --force-recreate app`.
- ✅ **Phase 3b - 남은 study 측정 완료 (③④)**
    - ✅ ④ 캐싱/관측: **Redis 분산 캐시**(재시작·다중 인스턴스에도 유지). 같은 질문 2회 → **8,139ms→0ms, LLM 1→0회**. 재시작 후에도 `cached:true` 확인. + **관측성 대시보드** `grafana/provisioning/dashboards/rag-observability.json`(6패널: 처리량·p95/p99·JVM스레드·힙·CPU·캐시) - 부하 걸며 실시간 관측. Grafana `http://localhost:3001` → "RAG 서비스 관측 대시보드".
    - ✅ ③ MySQL 검색 최적화: **인메모리 벡터 인덱스**(`InMemoryVectorIndex`)로 전환. 20k 청크 검색 **6,458ms→25ms(약 256배)**. RagService·/api/ask도 인덱스 사용. bench `/api/bench/search?mode=dbscan|memory`.
- ✅ **Phase A - 제품 전환 1차 (2026-07-05)**: `rac-doc` → **`ask-wiki`** 리네임(GitHub 레포·로컬 디렉터리·Java 패키지 `com.yeonwoo.askwiki`·앱/지표/대시보드 명). **파일 업로드**(`POST /api/documents/upload`, md·txt·pdf — PDFBox) + **웹 챗 UI**(`/`, 문서 등록·질문·출처 표시) 추가. ✅ **러닝 테스트 통과** (맥, 전체 스택): md 업로드→임베딩·저장, 미지원 확장자 400, 질문→정답+출처(1순위 정확, score 0.83, 6.3s), 동일 질문 캐시 히트(2ms), 문서 밖 질문 "모르겠습니다"(환각 억제). pdf 업로드는 실파일 미검증. ⚠️ 디렉터리 리네임으로 볼륨이 새로 생성됨(모델 재-pull 완료). 포트 충돌 시 `.env` 사용(로컬은 MYSQL_PORT=13306, GRAFANA_PORT=3001 사용 중).
- ✅ **Phase A-2 - UI 제품화 (2026-07-05)**: 웹 UI를 제품 수준으로 리디자인 — 디자인 토큰·Pretendard 폰트, 헤더(로고·서버 상태 표시), 드래그앤드롭 업로드 존, 문서 목록(호버 삭제), 채팅 버블·타이핑 인디케이터·출처 칩·응답시간/캐시 메타, 토스트 알림, 반응형. 크롬 실화면에서 질문→답변+출처 렌더링 확인 완료.
- ✅ **Phase B1 - 증분 인덱싱·정합성 (완료, 2026-07-05~07)**: 유령 재현(2건)→Outbox+relay로 create·delete 통일→relay-kill 유실 0→AtomicReference 세대 스왑→반영 지연 실측(pollMs=200: 평균 126.7ms). 테스트 스위트 13개 그린. 설계·측정은 design-notes §3, 세부 진행은 ROADMAP "Step 3 세부 진행".
    - ✅ Step 1: 유령 인덱스 재현 테스트(`GhostIndexTest`) — **빨간 불 재현 성공(2026-07-06): 롤백 후 유령 엔트리 2건 실측, DB는 0건.** 작성은 Codex CLI 위임, 실행·테스트 인프라 트러블슈팅(Docker Engine 29의 구식 API 400 거부 진단)은 Claude. 상세·재현 방법: ROADMAP "Step 1 세부 진행" + design-notes.md §3.
        - 2026-07-06 윈도우 PC에서 세션 재개. 작업 트리에 테스트 파일 없음 → Step 1을 여기서 진행. 환경 정리: 로컬 브랜치 `master`→`main` 정렬(업스트림 origin/main), 커밋 신원 레포 로컬 설정(§6), 네이티브 mysqld·redis와 포트 충돌 → `.env`(MYSQL_PORT=13306, REDIS_HOST_PORT=16379) 생성, 깨진 `core.sshCommand` 제거, 실수로 중첩 클론된 `ask-wiki/` 폴더 삭제. ⚠️ 이 PC의 SSH 키 2개 모두 GitHub 미등록 상태라 **푸시 보류 중**(`~/.ssh/id_ed25519_github_personal.pub`를 GitHub Settings → SSH keys에 등록하면 해결).
        - 2026-07-06 결정: 재현 테스트 DB는 **Testcontainers**(테스트가 전용 MySQL 8.4 컨테이너를 직접 기동 — 빈 DB라 절대값 단언 가능, 호스트 포트 충돌 무관, 추후 CI 편입 가능). compose MySQL 재사용안은 dev 데이터 오염(델타 단언 강제)·포트 오버라이드 의존·compose 기동 전제 때문에 기각.
    - ✅ Step 2: 설계 선택지 A~E 비교·결정 → **C(Outbox+relay) 메인**, B 비교·E 보정 관점 (design-notes §3).
    - ✅ Step 3: 3-1~3-2 Outbox 테이블·엔티티 → 3-3 create 쓰기 경로(유령 0) → 3-4 relay(멱등 2겹) → 3-5 relay-kill(유실 0) → 3-6 삭제 통일·AtomicReference 세대 스왑 → 3-7 반영 지연 실측. 전부 Codex 위임·Claude 검증(3-7 측정 하네스만 Claude 직접). 세부는 ROADMAP.
        - **B1 마감 (2026-07-07)**: ⚠️ 3-7 커밋은 **미커밋 상태로 작업 트리에**(연우님 커밋·푸시 예정, `c5dbf76`(3-6)도 미푸시). 다음 Phase = **B2 답변 품질 평가 하네스**(ROADMAP). W-1(CLAUDE.md·금칙어 훅)도 병행 가능.
- ✅ **Phase B2 - 답변 품질 평가 하네스 (완료, 2026-07-07)**: golden set(HR/총무 8문서·질문 50개) + `@Tag("eval")` 러너 **4종**(HitRate·ChunkSizeMatrix·Hallucination·ScoreDistribution, `./gradlew evalTest`, Ollama 필요). **실측**: hit rate@4=93.3%(청크 500·topK 4 유지), 환각률(프롬프트 4종 + 임계값까지 조사) → **로컬 소형 모델(llama3.2:3b+nomic)의 환각 바닥은 프롬프트·임계값으로 못 넘음 → 진짜 레버는 C2(더 강한 모델)**. 배포 프롬프트 = v4(오거부 3.3%, UX 우선). **핵심 발견: (1) Phase A 1회 확인은 거짓 안심(체계 측정하니 환각 65%) (2) 임계값 가설을 구현 전 측정으로 기각(점수 분포 겹침).** 상세: design-notes §3, b2-prompt-experiments.md. ⚠️ golden set 사실관계는 연우님 검수 권장.
- ✅ **Phase C2 - 상용 LLM 프로바이더 스위치 (완료, 2026-07-10~12)**: B2가 정량적 동기 제공 — 로컬 소형 모델의 환각 바닥(65%/60%)을 상용 모델이 깨는지 B2 하네스로 증명. **확정 결정**: (1) 세 개(Claude/GPT/Gemini) 모두 스위치 가능하게(프로퍼티 `askwiki.llm.provider`), 우선 1개 붙이고 확장 (2) **챗 LLM만 교체**·임베딩 nomic 유지(→ 환각↓ 원인을 LLM 판단으로 귀속하는 실험 통제) (3) B3(SSE) 미포함. 서브스텝 C2-1~C2-5(+배포)는 ROADMAP "Phase C2". 구 B4(장애 격리)·배포 과제 흡수.
    - **✅ C2-1 완료 (2026-07-10)**: `spring-ai-starter-model-openai`를 Gemini OpenAI-compat 엔드포인트로 붙이고 `askwiki.llm.provider`(ollama|gemini)로 스위치. 선택은 `LlmProviderEnvironmentPostProcessor`가 `spring.ai.model.chat` 토글로 처리. **13 테스트 그린**(provider=ollama·키 없이). google-genai 스타터는 1.0.9에 없어(Boot 3.5 필요) **A안(OpenAI 호환)** 채택. 함정: openai 스타터가 audio/image/moderation까지 오토컨픽→`none`으로 꺼야 키 없이 부팅. 상세 ROADMAP "Phase C2". (Codex가 Windows 런타임/patch 문제로 겉돌아 Claude가 직접 마무리.)
    - **✅ C2-2 완료 (2026-07-10)**: RagService `call(String)` → `call(Prompt)`/`ChatResponse` 전환, 답변 추출 + 토큰 usage 확보(현재 DEBUG 로깅, C2-3에서 Micrometer로). 컴파일·회귀 13 그린. 라이브 usage는 Ollama 필요(임베딩)라 C2-3/C2-5에서 관찰.
    - **✅ C2-3 완료 (2026-07-10)**: `LlmMetrics`가 llm.calls·llm.tokens{type}·llm.cost.usd·llm.latency를 provider 태그로 기록, RagService가 타이머로 감싸 record. 결정적 단위테스트 2개 → **전체 15 그린**(Ollama 불필요). Grafana 대시보드에 LLM 패널 4개 추가(토큰·지연 p95/p99·비용·호출). 실지표 라이브 관찰은 스택+Ollama 가동 시(C2-5와 함께).
    - **✅ C2-4 완료 (2026-07-10)**: `LlmCallGuard`로 LLM 호출 격리 — 세마포어(동시상한)·타임아웃·degraded 폴백(`RagResult.Degraded`, 검색 근거는 그대로 제공). 장애 주입 단위테스트 4개 → **전체 19 그린**(Ollama 불필요). 구 B4 흡수.
    - **🔧 C2-5 1차 진행 (2026-07-12, 맥)**: 하네스 보강(`[LLM-USAGE]` 사용량 요약·pacing 노브·evalTest 캐시 무효화·gradlew exec bit 복원) + **Ollama 재기준선 환각 75.0%/오거부 10.0%**(B2 60%/3.3%과의 차이 = 실행 간 요동 → 구간 60~75%로 해석, 같은 날·같은 머신 비교 기준선 확보) + **Gemini 2.5 무료 경로 실측으로 닫힘**(2.5-flash RPM 5·RPD 20 — 50문항 불가, 2.5-flash-lite 신규 차단; 3.1-flash-lite·3.5-flash 프로브 OK). 함정 3건(C2-4 가드 20s vs 콜드 로드 / Gradle up-to-date vs env 스위치 / Spring AI 429 비재시도)은 design-notes §4.
    - **✅ C2-5 완료 (2026-07-12)**: 계정 한도표 실측으로 3.5-flash도 RPD 20 → **gemini-3.1-flash-lite(RPM 15·RPD 500)가 유일한 무료 완주 경로**. 측정 결과 **환각 0.0%·오거부 6.7%** vs 같은 날 Ollama 기준선 75.0%/10.0% — **환각 바닥 돌파 + 두 지표 동시 개선**(프롬프트는 곡선 위 이동만 가능했는데 모델 교체는 곡선을 밀었다 = B2 "진짜 레버는 모델" 증명). 지연 mean 637ms(vs 8.8s CPU)·출력 토큰 1/5·비용 $0. application.yml 기본 Gemini 모델 3.1-flash-lite 확정, 회귀 19 그린. 상세: design-notes §4 비교표·해석·각주. (선택) 저비용 배포는 백로그 유지.
    - **C2 완료 (2026-07-12).** 평가 재실행법(env·pacing·워밍업)은 위 C2-5 불릿·ROADMAP C2 참조.
      > **C2-5 착수 메모 (다음 세션 turnkey)**:
      > 1. Docker Desktop 켜고 Ollama 기동 + 모델 pull: `docker compose up -d ollama` → `docker compose exec ollama ollama pull nomic-embed-text` + `docker compose exec ollama ollama pull llama3.2:3b`.
      > 2. JDK 21: `export JAVA_HOME=~/.jdks/temurin-21.0.5`. Gemini 키 export(.env는 gradle이 안 읽음): `export GOOGLE_GENAI_API_KEY=$(grep '^GOOGLE_GENAI_API_KEY=' .env | cut -d= -f2- | tr -d '\r')`.
      > 3. 프로바이더별 환각 측정 — 기준선(Ollama): `./gradlew evalTest --tests "*HallucinationEvalTest"` / 상용(Gemini): `ASKWIKI_LLM_PROVIDER=gemini ./gradlew evalTest --tests "*HallucinationEvalTest"` (임베딩은 여전히 Ollama).
      > 4. 환각률/오거부율을 **ollama vs gemini 비교표**로 `design-notes.md` §3 + ROADMAP C2-5에 기록 → "무료 강한 모델이 환각 바닥(65%/60%)을 깼나" 결론. 부수로 프로바이더별 토큰·비용·지연도 확인. **✅ Gemini 무료 경로 스모크 검증 완료**(2026-07-10): OpenAI-compat 엔드포인트에 `.env` 키로 직접 curl → HTTP 200·한국어 정상 응답·`reasoning_effort:none`으로 thinking 끄니 12s→1s·사고 토큰 0. → application.yml에 `spring.ai.openai.chat.options.reasoning-effort: none` 반영. 변경은 작업 트리 미커밋(연우님 리뷰·커밋 예정).
- 🔧 **Phase C1·C4·C3 - 채용 공고 대응 딥다이브 (2026-07-07 편성, 2026-07-11 강의 접목 보강)**: C1 벡터 DB 이행 ✅ → **C4 ask-wiki MCP 서버(신설, 다음)** → C3 에이전틱 RAG. 근거·매핑은 ROADMAP "채용 공고 대응" 참조. (C2는 위로 승격·완료.)
    - **✅ C1 완료 (2026-07-12)**: ES kNN 이행 — 자체 포트 `VectorIndex`+ES 어댑터(멱등 `_id`·kNN·점수 역변환), B1 정합성 4종 ES 재증명, 측정 5종(위). 다음 Phase C4(ask-wiki를 MCP 서버로 노출: `search_wiki`·`ask_wiki` tool, `spring-ai-starter-mcp-server-webmvc` — Boot 3.3+·1.0.x 포함 확인, C2-1식 함정 없음). 구현은 Codex 위임·측정/설계는 Claude(현 세션 확립 패턴).
    - **🔧 C4 착수 (2026-07-13)**: ask-wiki를 MCP 서버로 노출. **착수 전 검증**: `spring-ai-starter-mcp-server-webmvc`가 **BOM 1.0.9에 존재**(Maven Central maven-metadata 직접 확인 — C2-1식 버전 트랩 재현 안 됨), 앱이 서블릿 MVC(`spring-boot-starter-web`)라 **WebMVC SSE 변종이 정합**(기존 `/api/*`·`/`·`/actuator/*`와 무충돌), 등록 API = `@Tool` 메서드 → `ToolCallbackProvider` 빈(`MethodToolCallbackProvider`). **설계 결정**: ① 2 툴 — `search_wiki`(검색만, C1 `VectorIndex`=ES kNN 경로 직접) / `ask_wiki`(풀 RAG). ② **`ask/AskService` 추출**(캐시 조회→RAG→캐시 저장 오케스트레이션을 `/api/ask` 컨트롤러와 MCP `ask_wiki`가 공유 — 연우님 결정, `AskController` 소폭 리팩터·동작 불변). ③ 인증 없는 로컬 SSE(경량 데모 범위). `docs/DESIGN.md`에 `mcp/` 패키지·SSE 엔드포인트·AskService 재사용 반영. 전 프로젝트 공통 규칙대로 `AGENTS.md` 신설(기존 `docs/` 정본으로 defer). Codex 위임(2 툴+AskService+결정적 단위테스트 3종)·Claude 검증.
    - **✅ C4 완료 (2026-07-13)**: 8파일 신설(`ask/AskOutcome`·`ask/AskService`·`mcp/WikiAnswer`·`mcp/WikiMcpTools`·`mcp/WikiMcpConfig` + 테스트 3)·3파일 수정(build.gradle·application.yml·`ask/AskController`). **전체 스위트 39/39 그린**(Testcontainers MySQL 8.4+ES 8.17.4 풀 컨텍스트). 검증 중 **순환 의존성 발견·수정**: 챗 모델 tool-calling 인프라(`toolCallbackResolver`)가 기동 시 모든 `ToolCallbackProvider`를 즉시 수집 → `wikiTools→WikiMcpTools→AskService→RagService→ChatModel(생성중)` 순환(BeanCurrentlyInCreation) → `WikiMcpTools`의 AskService 주입 `@Lazy`로 절단(런타임 영향 0, **단위테스트는 못 잡고 풀 컨텍스트 부팅이 잡음** = design-notes §6). 구현 Codex·검증/순환수정 Claude(샌드박스가 gradle 막아 Codex가 못 보는 유형). C4 구현 완료 → 라이브 데모·마감은 아래. 다음 Phase = **C3 에이전틱 RAG**.
    - **✅ C4 라이브 데모·마감 (2026-07-14)**: Codex CLI에 ask-wiki MCP 연결 성공(stdio + `mcp-remote` → 우리 `/sse`, **로컬·터널 불필요**). 함정 3건: ChatGPT는 **Streamable HTTP만** 지원(우리 SSE와 불일치)·`codex exec` 비대화형은 툴 **승인 부재로 취소**(대화형은 정상, Inspector로 실호출 검증됨)·supergateway 브리지는 **세션 불일치**로 실패 → mcp-remote로 우회. **"무조건 wiki"는 모델 자유 제거**가 정답(@멘션 Custom GPT / 웹 UI 순수 RAG). 포트폴리오 스크린샷 `docs/images/ask-wiki-mcp.png` 커밋(`fbd9c1f`). 연결 도구(브리지·터널)는 데모용이라 레포 미포함. 상세 글감은 design-notes §6 추가 예정.
    - **🔧 C3 착수 (2026-07-14)**: **프레임워크 = Spring AI Tool Calling 확정**(연우님, LangChain4j 기각 — ROADMAP "Phase C3" 신설). 3요소(에이전틱 루프·Structured Output 분류·ChatMemory 멀티턴). 당시 다음 스텝 = C3-1 설계. 정직한 전제: 강한 모델(Gemini) 필요 + 단일홉 코퍼스엔 이득 안 보일 수 있어 멀티홉 golden 확장 필요.
    - **✅ C3-1 구현 (2026-07-14)**: `WikiSearchTool`(per-call 도구·소스 수집기, 전역 빈 아님)·`AgenticRagService`(ChatClient 도구 루프 → RagResult, 단발 `RagService` 유지). 결정적 단위테스트 5종(fallback — 목 ChatModel은 Spring AI 내부 tool 루프를 못 돌려서 도구·매핑을 분리 테스트). **전체 스위트 44 그린**(컨텍스트 부팅 OK, C4식 순환 없음). Gemini tool-calling 선검증 통과. Codex 위임·Claude 검증.
    - **✅ C3-1 측정 완료 (2026-07-14) = 정직한 한계**: 멀티홉 골든 6문항으로 단발 vs 에이전틱 비교. **발견 1** 강한 모델 경로 막힘 — Gemini(OpenAI-compat)는 다중턴 tool에서 `thought_signature` 미echo로 **HTTP 400**(단일턴 RAG는 완벽, C2). **발견 2** 약한 모델(Ollama 3B)은 에러 없이 돌지만 **멀티홉 이득 없음**(단발 2/6 vs 에이전틱 1/6 @topK4, 둘 다 0/6 @topK2·더 느림 — 3B가 재검색을 제대로 안 씀). **결론**: 에이전틱 값어치는 강하고 네이티브 tool-calling 하는 모델이 있어야 드러남 → 현 무료/로컬 스택으론 미증명(B2-5 계열 정직한 부정). 하네스 `MultiHopComparisonEvalTest`+`eval/questions-multihop.json`. 상세 design-notes §7. **C3-1 마감.** 다음 레버(강한 네이티브-tool 모델: Claude 키 또는 Spring AI 1.1)는 유보, 연우님이 **C3-2(질문 분류/라우팅)를 다음 세션으로 선택**(2026-07-14).
    - **✅ C3-2 구현 완료 (2026-07-14)**: 질문 분류/라우팅. **핵심: 단일턴 Structured Output(`ChatClient.entity()`)이라 C3-1의 다중턴 tool 벽(Gemini `thought_signature`)을 피해 무료 Gemini로 실측 가능** — 추상화의 한계 지점(단일턴 OK/다중턴 X)을 두 스텝이 가른다. 설계(연우님 확정): 신설 `routing` 패키지(`QuestionType`{WIKI·CHITCHAT·AMBIGUOUS}·`QuestionRoute(type,message)`·`QuestionRouter.classify`) → **`AskService` seam에 라우팅**(C4가 뽑은 오케스트레이션이라 `/api/ask`·MCP `ask_wiki` 자동 적용): CHITCHAT→`Answered(message,[])`·AMBIGUOUS→**신설 `RagResult.Clarify(message)`**·WIKI→기존 RAG 낙하. 결정 3: ① 되묻기·즉답 message는 **분류 호출이 함께 생성**(1콜) ② `askwiki.routing.enabled` **기본 off**(현 동작·44 그린 불변 — default-on이면 결정적 스위트가 Ollama 분류를 요구) ③ classify 실패 **fail-open→WIKI**. 분류 정확도 측정(golden set·eval)은 **C3-4로 분리**(범위 = 코어만, 연우님 확정). 구현 Codex 위임·**Claude 검증 완료**: 10파일(신설 `routing/` 3 + 테스트 2, 수정 `RagResult`·`AskService`·`AskController`·`WikiMcpTools`·`application.yml` + eval 테스트 2) — **전체 49 그린**(44→+5: 라우팅 4분기·캐시 우선·fail-open 결정적 단위테스트). 검증 중 Claude 수정 2건: ① Codex가 프롬프트·테스트에 넣은 denylist 금칙어→`사내`로 치환(커밋 차단 회피) ② 스코프 밖 eval 테스트 2곳(`HallucinationEvalTest`·`MultiHopComparisonEvalTest`)의 `RagResult` switch에 `Clarify` arm 추가(sealed 컴파일러가 잡음 — 위임 스펙에서 Claude가 누락, C4식 "누락 switch는 컴파일이 잡는다"). 풀 컨텍스트 부팅 OK = QuestionRouter/AskService 새 배선에 C4식 순환 없음. **Codex 산출물 리뷰 (2026-07-14)**: 스펙 준수 100%(지정 파일만·커밋 안 함·docs 미접촉)·캐시 단락 순서까지 정확 → 실행 품질 좋음. **결함 1건 즉시 수정**: 비-WIKI 분류인데 LLM이 `message`를 빠뜨리면 `Answered(null,[])`/`Clarify(null)`이 그대로 나가던 것(`"answer": null`)을 **`normalize()` 순수 함수**로 차단 — "이행 불가한 분류 = 분류 실패" → 기존 fail-open WIKI로 흡수(캔드 문구 신설 없이 모듈 철학 확장), `entity()` 성공 경로는 목 불가라 순수 함수 분리가 곧 테스트 전략(`AgenticRagService.mapResult`와 동일 패턴) + 결정적 테스트 2종 → **전체 51 그린**. **이월 2건 = C3-4**(연우님 결정, 라우팅을 실제 켜는 단계): 분류 호출이 `LlmCallGuard`(hang 무방비 — try/catch는 예외만 잡음)·`LlmMetrics`(토큰·비용 과소 집계)를 **둘 다 우회**. **글감**: 같은 위임에서 sealed switch 누락은 **컴파일러가 잡았지만** 가드·지표 누락은 **아무도 안 잡는다** → 횡단 관심사(장애격리·관측)는 위임 스펙에 명시해야(design-notes §7, W-3/W-4 글감). **C3-2 완료(코어+하드닝).** 라이브 분류 정확도·라우팅 하드닝은 C3-4.
    - **✅ C3-3 완료 (2026-07-14)**: 멀티턴 ChatMemory. **핵심 통찰: 멀티턴 RAG의 진짜 문제는 "LLM이 맥락을 아느냐"가 아니라 "검색이 맥락을 아느냐"** — Spring AI ChatMemory 어드바이저는 *채팅 프롬프트*에만 히스토리를 넣는데 RAG는 LLM보다 **먼저** 임베딩·검색한다. "그럼 반차는?"을 그대로 임베딩하면 쓰레기가 검색됨 → **질문 재작성(condensation)**: 히스토리+follow-up → 독립형 질문 → 임베딩·검색. 재작성도 **단일턴이라 C3-1의 다중턴 벽 무관**(C3-2와 동일 구조). **1.0.9 실체 검증(C2-1 교훈)**: `ChatMemory`·`MessageWindowChatMemory`·`ChatMemoryRepository`·`InMemoryChatMemoryRepository`+오토컨픽 존재, Redis/JDBC repo는 없음. **오토컨픽 배당 발견**: `chatMemoryRepository()`·`chatMemory()` 둘 다 `@ConditionalOnMissingBean`이고 후자가 주입 repo로 `MessageWindowChatMemory`를 빌드 → **repository 빈 하나만 제공하면 나머지가 공짜로 조립**(ChatMemory 빈 직접 정의 불필요, C2-1식 충돌 없음). 설계 결정(연우님 확정): ① 저장소 = **자체 `RedisChatMemoryRepository`**(SPI 4메서드, Redis 이미 있음 → 재시작·멀티 인스턴스 안전 = **C1 교훈을 처음부터 적용**, 인메모리 재현 회피). 다형 직렬화 리스크는 `Message` 인터페이스를 직접 직렬화하지 않고 `{type,text}` record로 우회 ② 범위 = **코어 + 웹 UI**(진짜 챗봇) ③ **캐시 키를 standalone으로** — 현 `QueryCache`는 질문 텍스트 키라 멀티턴이면 "그럼 반차는?"이 대화 간 **충돌해 오답 반환**(실제 버그). standalone은 문맥 독립이라 안전 + 히트율↑(다른 표현이 같은 standalone으로 수렴) ④ **첫 턴은 재작성 안 함** → 단일턴 무회귀(추가 콜 0), 턴 2부터만 1콜 ⑤ **생성 프롬프트엔 히스토리 미주입** — 질문이 이미 자기완결적이니 생성은 검색 컨텍스트에만 근거(히스토리로 답하는 환각 차단 = B2가 65%까지 측정한 그 성질 보존). 구현 Codex 위임·**Claude 검증 완료**: 10파일(신설 `conversation/` 2 + 테스트 2, 수정 `AskService`·`AskController`·`AskRequest`·`application.yml`·`index.html`·`AskServiceTest`) — **전체 63 그린**(51→+12), 풀 컨텍스트 부팅 OK = **Redis repository 빈이 오토컨픽 InMemory를 정상 대체**(`@ConditionalOnMissingBean` 예측 적중, 충돌·순환 없음). 리뷰 결과 **스펙 준수 100%**(다형 직렬화 회피·첫 턴 재작성 생략·standalone 캐시 키·원본 질문 기록 전부 정확). Claude 수정 2건: ① 재작성 프롬프트에 영어 "becomes"가 섞이고 한 줄로 뭉쳐 있던 것 → 코드베이스 불릿 스타일로 정리(프롬프트 = 실제 동작이라 그냥 못 넘김) ② `crypto.randomUUID()`는 **보안 컨텍스트에만 존재** → 평문 HTTP 배포 시 초기화 TypeError로 **UI 스크립트 전체 사망** → 폴백 1줄 추가(백로그 배포 과제 대비). **C3-3 완료.**
    - **🔧 C3-4 착수 — a) 하드닝 구현 위임 (2026-07-14)**: C3 전체를 측정으로 닫는 Phase. 3덩어리 = **a) 라우팅/재작성 하드닝(이월분) → b) golden set → c) 라이브 측정**. **발견: 이월 부채가 복제됐다** — C3-2에서 미룬 "분류 호출이 `LlmCallGuard`·`LlmMetrics`를 우회" 결함을 C3-3의 `QuestionRewriter`가 **똑같이 반복**(스펙에 안 적었으니 또 빠짐 = §7 "횡단 관심사는 명시해야"의 실증). 이월 2건 → **2개 호출 지점**으로 불어남. **a) 설계**: ① `LlmCallGuard`에 제네릭 코어 `<T> T call(Supplier<T>)` 추가(기존 `call(ChatModel,Prompt)`은 델리게이트로 보존 → RagService·테스트 4종 불변) ② `LlmMetrics`에 **`purpose` 태그**(answer|classify|rewrite) — **Prometheus는 같은 이름의 미터에 동일 태그 키를 강제**하므로 기존 answer 경로까지 **한꺼번에** 붙여야 함(혼재 시 레지스트리 에러). 기존 Grafana 5패널은 전부 `sum() by (provider,...)`라 **라벨 추가에 하위호환**(수정 불필요) ③ 두 호출부를 가드+계측. **API 검증(C2-1 교훈)**: `CallResponseSpec.responseEntity(Class)` → `ResponseEntity<ChatResponse,T>`가 `getResponse()`(토큰 usage)와 `getEntity()`(파싱 결과)를 **한 번의 호출로 둘 다** 제공 → 분류기를 이중 호출 없이 계측 가능(`.entity()`와 `.chatResponse()`를 따로 부르면 모델이 2번 호출됨). 가드 실패(타임아웃·과부하)도 **fail-open 유지**(WIKI/원문) + `recordDegraded`로 가시화. **환경 실측**: Docker ✓·Gemini 키 ✓·**Ollama 꺼짐**. → **분류 정확도 측정은 임베딩이 불필요해 Gemini만으로 가능**, 재작성(멀티턴) 측정은 검색을 타므로 Ollama 기동+모델 pull 선행. **✅ a) 하드닝 완료 (2026-07-14)**: 9파일 수정 — **전체 68 그린**(63→+5). `LlmCallGuardTest` 기존 4종이 **수정 없이 통과**(델리게이트가 RagService 경로를 보존한 증거) + 제네릭 코어 2종, `LlmMetricsTest`에 purpose 독립 기록 1종, 분류·재작성 각각 `fallsOpenAndRecordsDegradedWhenGuardTimesOut` — **이전엔 막을 수 없던 hang이 이제 타임아웃→fail-open→degraded 지표로 관측된다**(이월 부채 해소). Claude 검증 수정 3건: ① `QuestionRewriter`가 `record()`를 **null 방어 검사보다 먼저** 호출해 null 응답 시 record 내부에서 NPE(fail-open에 먹혀 무해했지만 방어 검사가 무의미해짐) → 순서 교정 ② `LlmMetrics`의 고아 주석(가격표 설명이 purpose 상수 위에 남음) 정리 ③ `LlmCallGuard` 빈 줄 정리. **✅ b·c) 멀티턴 재작성 A/B 측정 완료 (2026-07-14) = C3-3 핵심 주장 증명 ⭐**: 골든셋 `eval/questions-multiturn.json`(2턴 대화 8쌍, 앵커 none 5·weak 3을 리깅 방지로 혼합) + 하네스 `MultiTurnRewriteEvalTest`. 같은 follow-up을 **OFF(원문 임베딩) vs ON(재작성 후 임베딩)** — 유일한 차이는 재작성. **실측 @4: OFF 62.5% → ON(Gemini) 100.0%(8/8)**, @2는 25%→75%. 앵커 없는 항목이 60%→100%로 최대 개선 = **이론이 예측한 지점에서 정확히 개선**. 실제 재작성 `"그날이 휴일이면요?"`→`"급여 지급일인 25일이 휴일이면 급여는 언제 지급되나요?"`. **대조**: Ollama 3B는 재작성 대신 **답변·환각**(`"식대는 하루 약 70만 원"` + 날조 이메일, `申请`·`พน` 언어 혼입) → @4 62.5↔75.0 **요동 = 노이즈**. **통제 검증**: OFF가 3회 실행 모두 정확히 62.5%(챗 모델 미사용이므로 당연 — 하네스가 재작성만 격리했다는 내부 증거). **C3 지형도 완성: 단일턴이냐 다중턴이냐가 운명을 갈랐다** — C3-1(다중턴 tool)은 막혀 미증명, C3-2·C3-3(단일턴)은 강한 모델로 되고 증명됨. 함정 1건: 윈도우 테스트 JVM stdout 인코딩(cp949)이 한국어 로그를 뭉개 정성 감사 불가 → `evalTest`에 `stdout.encoding=UTF-8` 고정(숫자는 영향 없었음, 출력 경로만). **✅ c) 분류 정확도 측정 완료 (2026-07-14) → C3-4 전체 완료**: 골든셋 `eval/questions-classification.json` 36문항(WIKI 15·CHITCHAT 12·AMBIGUOUS 9) + `ClassificationEvalTest`(임베딩 불필요). **라벨 원칙 = intent ≠ answerability**(코퍼스에 답 없는 육아휴직·퇴직금도 WIKI가 정답 — 검색 후 "모르겠습니다"가 옳은 동작). **실측: Gemini 97.2%(35/36) vs Ollama 3B 41.7%(15/36)**. Gemini는 WIKI·CHITCHAT recall 100%, AMBIGUOUS 88.9%, **위험 방향(WIKI→비WIKI) 누출 0**, 유일 오답 `"규정"`→WIKI는 프롬프트의 "애매하면 WIKI" fail-open이 **설계대로** 안전한 방향으로 틀린 것. **3B의 41.7%는 골든셋 WIKI 비율(15/36)과 정확히 일치 = 전부 WIKI로 찍는 축퇴 분류기**(CHITCHAT·AMBIGUOUS recall 0%) — 그런데 **fail-open 덕에 제품은 안 깨지고**(라우팅 끄기와 동일 동작으로 degrade) 토큰만 낭비(15,717 tok·콜당 2,476ms에 이득 0) → "약한 모델에선 라우팅 켜지 마라"가 측정으로 뒷받침됨(기본 off가 옳았다). **비용(purpose 태그 첫 수확)**: Gemini 분류 콜당 ~448 입력 토큰 → 라우팅은 입력 토큰 대략 +44%. **C3 종합**: 축1 호출형태가 강한 모델 접근을 정하고(다중턴=막힘/단일턴=가능), 축2 모델능력이 값어치를 정한다(3B=재작성 노이즈·분류 축퇴 / 강한 모델=재작성 100%·분류 97.2%) → C3-1의 "인프라가 막았다"가 반증 가능한 형태로 뒷받침됨. 함정: Codex가 쓴 테스트 파일에 **UTF-8 BOM**이 붙어 javac가 거부(`illegal character: '﻿'`) + Codex가 내 정상 JSON을 "invalid"로 오판 — 둘 다 PowerShell 툴링의 UTF-8 문제(§7). **✅ 골든셋 독립 검수 완료 (2026-07-14) — 4종 전부(B2 50·멀티턴 8·분류 36·멀티홉 6), 밀린 B2 검수 부채까지 해소**: 읽기 전용 감사를 **일반 에이전트**에 위임(관례상 Codex지만 직전에 Codex가 정상 JSON을 "invalid"로 오판하고 BOM을 붙인 전력 → 한국어 UTF-8 감사엔 부적격 판단). **결과: 사실 오류 0건** — expectedAnswer 30·turn1Answer 8·멀티홉 사실 12 전부 코퍼스 일치, **B2 환각률 타당성 확인**(unanswerable 20 중 답 가능한 것 없음). **진짜 수확 = 잡힌 게 사실이 아니라 내 서사였다**: ① `"규정"` 오답을 옹호하면서 골든셋에선 오답 채점 = 이중잣대 ② 대표 인용 `"그날이 휴일이면요?" — 주제어 0`이 거짓(`휴일`은 salary.md 단 한 줄에만 = 고유 검색 키) → none→weak 정정·층화 재집계(none 50→100 / weak 75→100) ③ "정확히 이론이 예측한 지점"이 1건 폭 마진에 얹힘 → 층화는 방향 참고용으로 격하, 지탱하는 주장은 **전체 62.5→100 하나**로 한정. **연우님 판정**: `"규정"` **AMBIGUOUS 유지 → 97.2% 유지**(6개 규정 문서 중 특정 불가 → 되묻기가 나은 UX), 나머지 3건(u13 오채점 가능성·B2 hard 버킷 오라벨·멀티홉 m3가 정답과 그 부정을 구분 못 함)은 **각주로 기록만**(라벨 방어 가능·영향 작음·이미 끝난 Phase). 교훈: **자기 데이터·자기 채점·자기 해설의 삼중 고리에선 숫자가 아니라 프레이밍이 샌다 → 정직성은 측정 설계가 아니라 리뷰 구조로 지켜진다**(design-notes §7). **C3 전체 완료 + 골든셋 검수 완료.**

> **지금 여기 (2026-07-16) = 🔧 Phase B5(하이브리드 검색) 착수 — Step 1 결정까지 끝, 다음은 B5-1 구현.** 메인 시퀀스(B1→B2→C2→C1→C4→C3) · W · 골든셋 검수 · Phase D는 완료. B5 상세는 ROADMAP "Phase B5", 남은 잔가지는 **§5-1**.
> ⚠️ 단 **"캡처 2건 완료"는 사실이 아니었다** — 채팅 캡처는 **파일이 커밋되지 않아 공개 페이지가 깨져 있다**(§5-1 B, 2026-07-16 실측 정정).
> **§5-1의 ⭐(골든셋 9번째 쌍)은 2026-07-16 기각으로 닫혔다** — 넣어서 3회 재보고 되돌렸다(train-on-test + 판별력 0). 공개 숫자·골든셋 8쌍 불변. 그 대신 **B5에 정량적 동기가 생겼다**(§5-1 C).
>
> **결정 기록**: **배포는 하지 않는다**(연우님, 2026-07-16 — 로컬에서만 실행). 따라서 Phase D의 "Products 등재 조건(데모 가능한 배포)"은 **해당 없음**이고 케이스 스터디는 `category: study`로 간다(실제 그렇게 작성됨). 백로그 재평가(2026-07-14): **B3(SSE)는 전제 소멸** — 동기였던 "8~15초 체감"이 Gemini 637ms 실측으로 사라졌다(느린 건 CPU 추론이었지 아키텍처가 아니었다). **B5(하이브리드 검색)는 자연스러워짐** — C1이 ES를 고른 근거가 "B5가 같은 엔진에서 확장"이었으니 BM25+kNN RRF를 B2 하네스로 증명하면 그 약속 회수(단 hit rate@4가 이미 93.3%·@8 100%라 **헤드룸이 얇다** — 승산 있는 각도는 @1 36.7%·@2 56.7% 정밀도이고, 오르면 "topK 4→2로 컨텍스트 토큰 절반" 서사와 연결). **2026-07-16 보강: @4에도 구체적 실패 사례가 생겼다**(멀티턴 `vacation` 5위/8 — 어휘는 정확히 일치하는데 벡터가 못 올린다) → 승산 각도가 하나 늘었고, 성공하면 ⭐ 재개 조건까지 열린다(§5-1 C).
    - **🔧 C1 착수 (2026-07-12)**: ROADMAP에 "Phase C1" 섹션 신설 — 문제 정량화(20k=60MB·25ms지만 O(N) 선형 + **멀티 인스턴스 정합성 결함 발견**: relay가 PROCESSED 마킹하면 다른 인스턴스는 그 이벤트를 못 봐 사본이 어긋남 = 단일 인스턴스 전제), 후보 5종 비교표(ES kNN·Qdrant·pgvector·Redis+RediSearch·유지), 벡터 스토어 스타터 4종 **1.0.9 존재 검증 완료**. Step 1 ✅ 결정(2026-07-12, 연우님): **ES kNN + 자체 포트(`VectorIndex`)+어댑터** — 채택·기각 근거 design-notes §5.
    - **⚙️ 진행 방식 변경 (2026-07-12, 연우님 지시)**: C1 구현은 §7 step-by-step(직접 타이핑) 대신 **Codex 위임·Claude 검증**으로 전환. (W-3 사례 축적)
    - **✅ Step 2-1 완료 (2026-07-12)**: `VectorIndex` 포트 추출(동작 불변) — Codex 작성, Claude 검증(diff 리뷰 + 전체 19 테스트 그린) 후 커밋 `635431b`. Codex 샌드박스가 gradle 소켓/락을 막아 **테스트 실행은 Claude 몫**(B1 3-7과 같은 제약 패턴).
    - **✅ Step 2-2 완료 (2026-07-12)**: ES 인프라 — compose `elasticsearch:8.17.4`(single-node·보안off·512m힙·es-data 볼륨·`ES_PORT` 오버라이드), `elasticsearch-java`(Boot BOM 관리)+`testcontainers:elasticsearch`, lazy `ElasticsearchClient` 빈, `EsConnectivityTest`(Testcontainers 스모크). **Spring AI VectorStore 스타터 미사용 결정**(add()가 스토어에서 재임베딩하는 설계 — 우리는 임베딩을 MySQL에 보유, design-notes §5). Codex 작성·Claude 검증(diff+전 스위트 그린) `a7694e1`. 위임 중 함정: 서브에이전트 2분 타임아웃이 Codex 프로세스를 함께 죽임(파일 반영은 완료된 뒤였음) → **이후 위임은 --background 모드**로. **✅ Step 2-3 완료 (2026-07-12)**: `EsVectorIndex`(_id=chunkId 멱등 upsert·kNN(num_candidates≥100)·**점수 역변환 2s−1로 InMemory와 동일 [-1,1] 스케일**·deleteByQuery·bulk rebuild·refresh-on-write 프로퍼티) + `VectorIndexConfig`(@Primary 선택, `askwiki.vector-index.impl`=memory|elasticsearch) + InMemory 시작 rebuild를 memory 모드일 때만 + 계약 테스트 4종(멱등 add·랭킹/스케일·범위 한정 삭제·rebuild). Codex 작성(--background 위임)·Claude 검증 — **컴파일 에러 1건(닫는 괄호 누락) Claude가 수정**(Codex 샌드박스가 컴파일 불가라 남는 슬립 유형), 전 스위트 10클래스 그린. **✅ Step 2-4 완료 → Step 2 전체 마감 (2026-07-12)**: `EsOutboxIntegrationTest` 4종 — 롤백 유령 0·relay 반영+재폴링 멱등·크래시 중간 add의 부분 색인이 _id upsert로 중복 없이 회복(무유실)·삭제 outbox 통합. Codex 작성·Claude 검증(수정 1건: **목 벡터를 768차원으로** — ES는 dense_vector dims를 강제, InMemory엔 없는 제약). 전 스위트 11클래스 그린, `66d9908`. **✅ Step 3-① hit rate 동등성 실측 (2026-07-12)**: 평가 러너 3종을 `VectorIndex` 포트 주입으로 전환(env `VECTOR_INDEX_IMPL`로 A/B) 후 — memory **93.3%@4**(B2와 완전 일치) vs ES **90.0%@4**(@1·@2·@8 완전 동일, 1문항 4↔5위 스왑 — **근사 아님**, num_candidates≥N 완전탐색·부동소수 코사인 경로 차. design-notes §5). 측정 조건: compose ES + `ASKWIKI_ES_REFRESH_ON_WRITE=true`.
    - **✅ Step 3 전체 완료 → C1 완료 (2026-07-12)**: 측정 5종 — ① hit rate 동등성 93.3 vs 90.0@4(부동소수 경로차, 근사 아님) · ② 20k 스케일 벤치(지연 역전 22.8→12.8ms, recall 0.83@nc100·dial, `rebuild()` OOM 발견→`BULK_BATCH_SIZE=500` 배치 수정) · ③ 반영 지연(ES refresh 계층 127→879ms, refresh-off 기본) · ④ 기동(ES 재시작 재빌드 0 vs 인메모리 6.3s) · ⑤ ETL(Chunker=TokenTextSplitter 8/8 수렴, 짧은 문서 → Chunker 유지). 하네스: `VectorIndexScaleBenchTest`·`EsReflectionLatencyTest`·`EtlComparisonEvalTest`(전부 `@Tag("eval")`). 상세 design-notes §5.
    - **2026-07-11 인프런 Spring AI 강의(JSCODE) 접목 확정** (3건 모두 연우님 승인): **P1** C1 보강 — ES 후보를 강의 실습으로 검증 + Spring AI `VectorStore` 인터페이스·ETL vs 자체 Chunker 설계 결정 추가 / **P2** C3 구체화 — Spring AI Tool Calling 우선·Structured Output 질문 분류·ChatMemory 멀티턴(+B2 멀티턴 golden set) / **P3** C4 신설 — MCP 서버(`spring-ai-starter-mcp-server-webmvc`, Boot 3.3+·1.0.x 포함 확인 — 버전 상향 불필요). **남은 순서 = C2-5 → C1 → C4 → C3.** 강의 섹션 2~5·7은 이미 초과 달성이라 접목 제외(입문 강의 — 딥다이브 차별화는 계속 B2 하네스 증명으로). 상세는 ROADMAP "강의 접목 메모"·각 Phase 불릿.
- ✅ **W - Claude Code 워크플로우 아티팩트화 (완료, 2026-07-07~14)**: W-1 커밋 가드 훅 2겹(git pre-commit + PreToolUse, secret-scanner 방식) · **W-2 커스텀 커맨드** `.claude/commands/{brief,record}.md`(둘 다 실제 반복에서 역산 — 모든 세션이 "두 문서 읽고 이어서"로 시작했고 진행 기록은 한 세션 10회 수작업) · **W-3·W-4 → `docs/ai-pair-workflow.md` 신설**(위임 11건 전수 사례 + 교훈 6 + 프로토콜 9조). 공고의 "Claude Code 활용 전문가(서브에이전트·MCP·훅)" **3종 모두 실물로 대응**: 훅=W-1, MCP=C4, 서브에이전트=W-3 + 커맨드=W-2. 핵심 교훈: **검증은 위임 불가(Codex는 gradle 실행 불가)라서 "구현 위임·검증 보유" 분업이 취향이 아니라 제약에서 나왔다**, **타입이 강제하는 누락만 반복을 면한다**(sealed switch는 잡혔고 가드·지표는 다음 스텝이 복제), **자기 데이터·자기 채점·자기 해설은 프레이밍이 샌다**.
- 🔧 **Phase D - 포트폴리오 통합 (1차 완료, 2026-07-16)**: 케이스 스터디 + devlog 8편을 포트폴리오 사이트에 **작성·커밋 완료**. 체크리스트는 `docs/ROADMAP.md` 하단.
    - **⚠️ 작업 위치 = `C:\Users\SAMSUNG\personal`** (이 레포 아님). 같은 `ppupy1209/yeonwoo-dev` 레포이고 **여기가 연우님의 실제 작업 사본**이다. 별도로 클론한 `yeonwoo-dev/`는 이관 후 삭제함(빈 폴더만 잠겨 남아 있을 수 있음 — 재부팅 시 사라짐).
    - **커밋 4개, 브랜치 `feat/web-monochrome-redesign`, origin 대비 ahead 4 · 미푸시**: `8c2474f`(소개 문구 수정 — 연우님 진행 중이던 작업) · `d5fbbbb`(status 기록) · `4585830`(**Ask Wiki 케이스 스터디**) · `a60e4f4`(status 기록).
    - **작성물**: `web/content/projects/ask-wiki.mdx`(category **study**·order 3·지표 배지 환각률 75%→0%, **간단한 개요만** — 깊이는 devlog으로) + `web/content/devlogs/*.mdx` **8편**(rag-basics/eval-harness/hallucination-floor/ghost-index/vector-index-migration/multi-turn-rewrite/agentic-rag-limit/mcp-server) + 아키텍처 **3중 구조**(`architecture.ts`·`arch/AskWikiArch.tsx`·`ArchitectureDiagram` DIAGRAMS 맵) + `public/projects/ask-wiki-mcp.png`. **빌드 31 정적 페이지·린트 클린 검증**.
    - **연우님 지시로 반영한 원칙**: ① **채용·이직 키워드 전면 금지** — 이 레포 문서의 "공고 대응" 프레이밍은 포트폴리오 글에 **한 글자도 넣지 않음**. 대신 **학습 목적 명시**(인프런 Spring AI 강의 + 공식 문서 참고, 형제 study 2편의 기존 관례와 일치) ② **기초 포함**(RAG란·임베딩이란·코사인·청킹 → devlog `rag-basics`) ③ **Spring AI 첫 사용 티가 나게** — 실제 밟은 함정을 그대로: `call(String)`이 토큰 usage를 버림 / openai 스타터가 audio·image까지 오토컨픽해 부팅 실패 / 도구를 전역 빈 등록해 순환.
    - **사이트 문체 규칙(파악 결과, 반드시 지킬 것)**: **산문 없음 — 전부 불릿**, 한 줄 = 한 사실, 볼드는 **지표에만**, `기술 선택 이유`에 **기각한 대안도 명시**(`Qdrant 제외` 식), 코드 블록 1개+한글 인라인 주석, `검증`은 `테스트 설정`/`측정 결과`. devlog frontmatter는 `project: ask-wiki`만 넣으면 **등록 코드 0**으로 자동 목록화. devlog summary는 `[문제 단정문]. [해결 요약]` 훅.
    - **⚠️ 그 레포의 함정 2가지**: ① **fresh clone은 전역 git 신원을 상속한다** — 이 PC의 전역 설정은 개인 계정이 아니고, 그 레포 CLAUDE.md는 개인 계정만 허용한다. **클론 직후 `git config user.email` 확인·고정 필수**(현재 `personal`은 개인 계정으로 로컬 고정해 둠) ② 커밋 컨벤션 = `<type>(<scope>): 한글`, scope는 web·backend·docs·infra, **웹 변경과 `docs/collab/status.md` 기록을 별도 커밋으로 분리**하는 선례.
    - **✅ Phase D 마감 — 캡처 2건 + 실측 정정 (2026-07-16)**: ① **웹 UI 채팅 캡처**(Claude 직접 촬영, Gemini 경로·2턴·실지연) → `personal`의 `ask-wiki.mdx` 개요에 삽입 ② **MCP devlog 캡처 렌더 확인**(오래 미확인이던 항목) ③ **§5-1 A 두 건은 이미 끝나 있었다** — ask-wiki는 GitHub main = 로컬 HEAD `67eda18` 일치, personal 4커밋은 **PR #41로 머지 완료**. 문서가 "미푸시"로 3일간 잘못 기록하고 있었다(교훈: 푸시 상태는 로컬 추적 ref가 아니라 **원격에 직접 물어야** 한다 — `origin/main` ref가 없으면 `git log origin/main..HEAD`는 조용히 빈 결과를 낸다).
        - **🐛 발견·수정 — `docker compose up` 경로에서 health가 항상 DOWN이었다**: ES 설정이 두 갈래인데 한쪽만 배선됨. 앱의 ES 클라이언트는 `askwiki.es.url`(=`ES_URL`)을 쓰는데 **actuator 헬스 인디케이터는 Boot 표준 `spring.elasticsearch.uris`를 쓰고 아무도 안 채워서** 컨테이너 안에서 `localhost:9200`을 찔러 refused. 결과: **§4가 성공 기준으로 못박은 `curl .../health # {"status":"UP"}`이 이 경로에선 통과한 적이 없고**, 웹 UI 헤더 점도 빨강이라 캡처가 불가능했다. `application.yml` 3줄로 같은 `ES_URL`에 물림 → **68/68 그린**(회귀 0), 헤더 초록 "정상 동작 중". **교훈: 78d363e(compose가 품질 스위치를 앱에 전달)와 같은 계열 — 문서가 약속한 실행 경로를 실제로 밟은 사람이 없으면 약속은 조용히 거짓이 된다.**
        - **🐛→✅ 재작성기 과잉 특정 — 발견·수정·재측정 (2026-07-16)**: 2턴 데모를 만들다 `"그럼 언제까지 써야 하나요?"`가 "모르겠습니다"로 끝나 파보니, 재작성기가 앞 답변의 "입사 1년 후"를 주워 **사용자가 묻지 않은 "입사일로부터"**를 심어 `vacation`을 top-4 밖으로 밀어냈다(3회 재현). **fail-open이 아니라 성공한 호출이 틀린 것**이라 예외가 아니라 **지표로만** 잡혔다(`llm_calls{purpose="rewrite"}=1`·`llm_degraded` 없음 — purpose 태그의 두 번째 수확). **수정** = 프롬프트에 "채우기만 하고 덧붙이지는 마세요" 한 조 + 문제의 O/X 예시. **같은 날 A/B(프롬프트만 변수)**: **@4 100% 유지(회귀 0)·@1 50.0→62.5%**(군더더기를 빼니 순위도 날카로워졌다), **OFF는 before/after 완전 동일 = 통제 검증**(5회째). 재작성 문장이 8쌍 전부에서 깎였고(`"급여 지급일인 25일이"`→`"급여 지급일이"`) **진짜 대명사 해소는 살아남았다**(`그날`→`급여 지급일`). **버그 케이스 e2e 3/3 PASS**(수정 전 3/3 FAIL과 대칭), 결정적 스위트 68/68. **프롬프트는 문자열이라 컴파일이 안 잡는다 → eval + e2e 재현이 유일한 검증 경로**였다. 남은 판단(골든셋 9번째 쌍) = §5-1 C 첫 항목.
        - **⭐→❌ 골든셋 9번째 쌍 편입 판정 = 기각 (2026-07-16, 맥)**: §5-1이 "진짜 마무리"로 남겨둔 그 일을 **실제로 해보고 되돌렸다**. 넣고 3회 재니 **OFF 55.6 → ON 88.9**(예측 55.6→100이 틀렸다 — 9번째 쌍이 ON에서도 MISS). 사유 ① **train-on-test**: 재작성기 프롬프트의 O/X 예시가 그 케이스 **자체**(입력 verbatim + O 정답 = Gemini 산출과 동일) → 복사를 재는 것. 3B가 그 예시를 **비밀번호 질문에까지 에코**한 게 증거. ② **판별력 0**: 고친 재작성도 5위/8 = MISS, 망가진 것도 MISS → 이미 빨간 테스트는 회귀를 못 잡는다. **골든셋 8쌍 복원 + 되돌린 뒤 재실행으로 공개 숫자 재현 확인**(`OFF 12.5/25.0/62.5 · ON 62.5/62.5/100`). 포트폴리오 숫자는 **손대지 않았다**. 남긴 것: 하네스에 **순위 진단**(`rank N` — 채점 무관, MISS가 근소 밀림인지 의미 실패인지 가름. 사유 ②는 이게 없었으면 못 봤다) + design-notes §7 새 절. **진짜 수확 = "재작성기는 고쳤는데 검색이 못 따라온다"** → `nomic` 임베딩이 C2의 통제(챗만 교체)가 남긴 **마지막 미당김 레버**임이 드러났고, B5에 정량적 동기가 생겼다(§5-1 C). 역설 1건: 3B의 **환각** 재작성이 어휘 중복만으로 `vacation`을 **1위**로 올렸다(정확한 재작성은 5위) → hit rate가 재작성 품질의 대리지표로 깨지는 구간 + BM25 가설의 직접 증거.
        - **🐛 발견·수정 — 커밋 전 금칙어 훅(W-1)이 맥·리눅스에선 처음부터 꺼져 있었다 (2026-07-16)**: `.githooks/pre-commit`이 **최초 커밋(`e4091ba`, 2026-07-09)부터 실행권한 없이(`100644`)** 들어 있었다. git은 **실행 불가능한 훅을 조용히 건너뛴다** → 공개 레포의 시크릿 가드 2겹 중 git 훅이 맥에선 무효. **윈도우에서는 진짜로 작동했다**(git이 훅을 `sh`로 돌려 실행 비트를 무시) → W-1의 "4개 케이스 테스트 통과"는 그 PC에선 사실이었고, 그래서 **아무도 못 봤다**. `gradlew` exec bit 유실(2026-07-12 복원)과 같은 계열. **수정** = `git update-index --chmod=+x`로 인덱스 모드 `100755` + 이 맥에 `core.hooksPath .githooks` 설치. **검증** = 금칙어가 든 줄을 실제로 커밋 시도 → **차단 확인**(권한만 세우고 끝내지 않았다). `claude-commit-guard.sh`는 `.claude/settings.json`이 `sh "..."`로 부르므로 실행비트 무관 = 안 건드림. **교훈: 918c4ca·78d363e와 같은 계열이 세 번째다 — 크로스 플랫폼에서 "한 PC에서 통과"는 "작동한다"가 아니다.**
        - **함정 2건**: ① `.env`에 **`ASKWIKI_LLM_PROVIDER`가 아예 없었다**(포트 오버라이드 전용으로 C2 이전에 쓰인 파일) → 키가 있어도 기본 3B·환각 75%로 찍힐 뻔. `.env.example`엔 있지만 이 PC의 `.env`는 그보다 오래됐다. ② 인앱 브라우저 screenshot이 30s 타임아웃(읽기·클릭은 정상) → **헤드리스 Chrome + CDP**로 우회(Node 22 내장 WebSocket, 의존성 0). 촬영 규칙은 `CLAUDE.md` "문서에 캡처 넣기"에 상설화(연우님 지시 — 캡처가 가독성을 크게 올린다).
    - **남은 것 = 아래 §5-1**.

- 🔧 **Phase B5 - 하이브리드 검색 (착수 2026-07-16)**: BM25(어휘) + kNN(의미)을 RRF로 융합. C1이 ES를 고른 근거의 회수. **승격 트리거** = 같은 날 멀티턴 판정에서 나온 벡터 단독의 구체적 실패 사례(흠 없는 질의가 `vacation`을 5위/8에 둠). 상세는 ROADMAP "Phase B5".
    - **✅ 착수 전 선검증 (제품 코드 0 — ES에 코퍼스만 넣고 질의)**: ① 문제 질의가 **BM25에선 1위**(벡터 5위) = **가설 통과, 착수 가치 있음** ② 문맥 의존 원문(`"그럼 언제까지 써야 하나요?"`)은 **BM25 0건**인데 벡터는 7위로라도 찾는다 → **두 방식이 서로 다른 실패를 한다 = RRF의 존재 이유가 실측으로 확인** ③ 과잉 특정 X안도 BM25 1위 → **"B5 성공 = 9번째 쌍 재개 조건"이라던 같은 날 내 주장은 오판**(하이브리드에서도 판별력 0)이라 정정.
    - **🚧 함정 3건 (착수 전에 잡음 — C2-1·C4와 같은 규율)**: ① **네이티브 RRF 사용 불가** — basic 라이선스가 `security_exception: non-compliant for [Reciprocal Rank Fusion (RRF)]`로 거부(실측) → **자체 구현**(공식 `Σ 1/(k+rank)`은 단순, "왜 표준을 안 썼나"가 D안 Kafka·VectorStore와 같은 서사로 남음) ② **nori 미포함**(8.x 기본) → 넣으려면 커스텀 이미지 + Testcontainers 전환 ③ **가장 큰 것 = ES에 본문 텍스트가 없다** — 색인 단위가 `IndexedChunk(documentId, seq, vector)`뿐이고 본문은 MySQL 하이드레이션(§5 "임베딩은 우리가 보유" 결정의 귀결) → **BM25로 검색할 대상이 아예 없어** 매핑 확장·색인 경로 변경·전량 재색인이 선결. **정직한 서사: C1이 ES를 고른 근거("B5가 같은 엔진에서 자연 확장")는 절반만 맞았다 — 엔진은 맞았고, 넣어둔 게 벡터뿐이라 데이터 모델을 넓혀야 한다.** ④ 부수: `VectorIndex.search(float[], int)`가 벡터만 받아 포트 확장 필요.
    - **✅ Step 1 결정 (2026-07-16, 연우님)**: 융합 = **자체 RRF**(네이티브 불가 실측). 분석기 = **standard로 먼저 재고 nori는 결과로 결정** — 기본 분석기가 조사 변형 4종에서 전부 `vacation` 1위였고(고정밀), 0건이면 벡터 단독과 같아져 회귀 위험이 낮다. 진짜 쟁점은 조사가 아니라 **recall**(후보 1~2건이라 RRF에 합칠 재료가 희박) → 그게 부족하다는 게 숫자로 나오면 그때 nori. **"측정 없이 정석을 도입하지 않는다."**
    - **다음 = B5-1** (ES 매핑에 본문 필드 + 색인 경로 + 포트 확장 방식 결정).

> 이 로그를 매 Phase 끝날 때 갱신한다(체크박스 ✅). 새 PC/세션은 이 로그의 "지금 여기"부터 이어가면 된다.

## 4. 실행 방법 (필요한 건 Docker 뿐, 자바 설치 불필요)

> ⚠️ **기본값(Ollama 3B)은 환각률 75%다.** 골든셋 실측이고, 프롬프트로는 못 내렸다(C2). **무료 Gemini 키 1개면 0%**가 된다(분류 97.2%·지연 0.6s). 품질을 보려면 A로, 구조만 보려면 B로.

```bash
# 임베딩 모델은 두 경로 공통 (약 270MB)
docker compose up -d ollama
docker compose exec ollama ollama pull nomic-embed-text

# A) 권장 — 무료 Gemini 키 (환각 0%). https://aistudio.google.com/apikey (카드 불필요)
cp .env.example .env      # ASKWIKI_LLM_PROVIDER=gemini · GOOGLE_GENAI_API_KEY=... 두 줄 수정
docker compose up --build -d

# B) 키 없이 (환각 75%)
docker compose exec ollama ollama pull llama3.2:3b     # 약 2GB
docker compose up --build -d

# 확인
curl http://localhost:8080/actuator/health      # {"status":"UP"} 나오면 성공
```

- 품질 스위치는 전부 `.env`로 오버라이드된다(`ASKWIKI_LLM_PROVIDER`·`GOOGLE_GENAI_API_KEY`·`VECTOR_INDEX_IMPL`·`ASKWIKI_ROUTING_ENABLED`). **2026-07-14 이전엔 compose가 이 값들을 앱 컨테이너로 넘기지 않아 C2 프로바이더 스위치·C1 ES가 `docker compose up` 경로에선 도달 불가였다** — 그때 고쳤다.
- 라우팅(C3-2)은 **기본 off 유지**가 맞다: 3B에선 전부 WIKI로 찍는 축퇴(41.7%)라 이득 0에 토큰만 쓴다. gemini일 때만 켤 것.
- 포트 충돌(로컬에 MySQL 3306 등이 이미 떠 있을 때): `.env.example`을 `.env`로 복사해 포트만 바꾸기
- 종료: `docker compose down`

## 5. 다른 PC / 새 대화에서 이어가기

**다른 PC에서:**
1. 그 PC에 Docker Desktop 설치
2. 아무 폴더에서 `git clone https://github.com/ppupy1209/ask-wiki.git`
   (※ 이 레포는 독립 프로젝트입니다. 다른 곳에 넣을 필요 없이 그냥 클론해서 열면 됩니다.)
3. 위 4번 실행 방법대로 실행
4. 작업 후 `git add -A && git commit && git push` 로 저장 → 어느 PC에서든 최신 상태로 이어짐
   (`.env`는 개인 설정이라 공유 안 됨. 새 PC에서 필요하면 다시 만들면 됨)

**새 AI 대화(Claude/Codex)에서 이어시킬 때 - 이렇게 말하세요:**
> "ask-wiki 레포에서 이어서 작업할 거야. `docs/START-HERE.md`, `docs/DESIGN.md`, `docs/ROADMAP.md`,
>  `docs/LEARNING-rag.md`, `docs/LEARNING-virtual-threads.md`를 먼저 읽고 현재 상태 파악해줘.
>  설계 결정은 나와 논의하고, 핵심 구현은 내가 직접 한다."

## 5-1. 남은 것 (새 세션은 여기부터) ⭐

> 2026-07-16 갱신. 메인 시퀀스·W·골든셋 검수·**Phase D(캡처 포함)**는 **끝났다**. 아래가 전부다.

### A. 푸시 계열 — ✅ 둘 다 이미 끝나 있었다 (2026-07-16 실측 정정)

- [x] **이 레포(ask-wiki) 푸시 완료** — GitHub `refs/heads/main` = 로컬 HEAD `67eda18`로 **정확히 일치**(공개 레포라 HTTPS `git ls-remote`로 인증 없이 대조). "미푸시 커밋 다수"는 **사실이 아니었다**. ⚠️ 단 SSH 키 미등록은 그대로다(`ssh -T git@github-personal` → `Permission denied (publickey)`) — 푸시는 HTTPS로 나간 것으로 보인다. SSH로 쓰려면 `~/.ssh/id_ed25519_github_personal.pub`를 GitHub Settings → SSH keys에 등록.
- [x] **`personal` 레포 머지 완료** — 4커밋(`8c2474f`·`d5fbbbb`·`4585830`·`a60e4f4`)이 전부 `origin/main`에 있고, 로컬 main이 GitHub가 생성한 **`dc8d33a "Merge pull request #41"`**을 담고 있다. 문서가 걱정하던 "새 PR이 필요할 수 있다"는 **이미 PR #41로 처리됐다**.

### B. 포트폴리오 (Phase D) — 작업 위치는 `C:\Users\SAMSUNG\personal`

- [ ] **⚠️ 웹 UI 채팅 화면 캡처 — 찍었지만 파일이 커밋되지 않았다. 공개 페이지의 이미지가 깨져 있다 (2026-07-16 실측 정정)**. 촬영 자체는 했다(Claude 직접, **Gemini 경로**·2턴 모두 근거 기반 정답·출처 4개·실지연 1.4s·811ms·찍기 전 `FLUSHALL`이라 캐시 히트 아님, 촬영법은 `CLAUDE.md` "문서에 캡처 넣기"). `projects/ask-wiki.mdx:19`의 이미지 태그도 `920c463`으로 **origin/main에 올라가 있다**. 그런데 **`ask-wiki-chat.png`는 git 이력에 한 번도 존재한 적이 없다**(`git log --all -- '*ask-wiki-chat*'` → 빈 결과, gitignore도 아님) — 파일은 윈도우 작업 사본에만 있고 **커밋 누락**이다. 당시 "dev 서버에서 렌더까지 확인"은 **거짓이 아니었다**: 로컬 작업 트리엔 파일이 있었으니까. 원격엔 없었을 뿐이다. **→ 918c4ca(health DOWN)·훅 exec bit와 같은 계열의 세 번째 반복: "내 PC에서 통과"는 "작동한다"가 아니다.** 해결 = 윈도우 PC에서 그 파일만 커밋하거나, 맥에서 재촬영(스택+Gemini 필요). 이 맥엔 파일이 없다(전체 검색 확인).
- [x] **MCP devlog 이미지 시각 확인 ✅ (2026-07-16)** — `/projects/ask-wiki/devlog/mcp-server`에서 정상 표시(`naturalWidth 790`, 깨진 링크 아님). 덤: DB에 남아 있던 정체불명 문서 `휴가 규정`(id=1, 2026-07-13)이 **그 MCP 데모의 잔재**임이 확인됨(캡처 속 문구와 일치).
- [ ] **(결정 필요) Virtual Threads·Redis 캐시 devlog 추가 여부** — 측정치는 좋다(처리량 538→1,257 req/s 2.3배 / 캐시 8,139ms→0ms·LLM 1→0회). 형제 `realtime-sensor-pipeline`(스레드풀 튜닝)과 결이 겹치고 RAG 서사 밖이라 **이번엔 뺐다**. 추가하면 **Grafana 캡처 필요**(`ask-wiki/docs/images/vthreads-{off,on}-grafana.png`에 이미 있음 — 재활용 가능).
- [ ] **이력서 반영** — ROADMAP D 체크리스트 참조. `personal`의 `web/content/resume.json` + `output/pdf/`.

### C. 기술 백로그 (선택)

- [x] **⭐ `입사일` 케이스를 골든셋 9번째 쌍으로 → 넣지 않기로 판정 (2026-07-16, 연우님)** — **실제로 넣고 3회 재본 뒤 기각**했다. 예측했던 "55.6→100"부터 틀렸다(실측 **55.6→88.9** — 9번째 쌍이 ON에서도 MISS라 100%가 깨진다). 기각 사유 둘: ① **train-on-test** — 2026-07-16 수정이 프롬프트에 박아 넣은 O/X 예시가 **그 케이스 자체**라(입력 verbatim, O 정답 = Gemini 산출과 동일 문장) 능력이 아니라 복사를 재게 된다. 증거는 3B가 그 예시를 **비밀번호 질문에까지 에코**한 것 ② **판별력 0** — 올바른 재작성도 @4 MISS(**5위/8**), 망가진 재작성도 MISS(top-4 밖) → **이미 빨간 케이스는 회귀를 못 잡는다**. 목적이 원리적으로 불가하고 공개 숫자만 잃는 교환이었다. **골든셋은 8쌍 그대로**이고 되돌린 뒤 재실행해 `OFF 12.5/25.0/62.5 · ON 62.5/62.5/100`으로 공개 숫자가 재현되는 것을 확인했다. 남은 수확 = **"재작성기는 고쳤는데 검색이 못 따라온다"**(→ 아래 B5). 상세·역설(3B 환각이 1위·정답이 5위)·새 규칙("골든셋 후보는 넣기 전에 제품 프롬프트를 grep")은 design-notes §7 "판정 — 9번째 쌍".
- [x] **B5 하이브리드 검색 → Phase로 승격·착수 (2026-07-16)** — 선검증 통과(BM25가 문제 질의를 1위로)·함정 3건 확인·Step 1 결정 완료. 더는 백로그가 아니다 → **ROADMAP "Phase B5"**·§3 진행 로그.
- [ ] **C3-1 재도전** — 에이전틱 RAG는 **미증명**으로 닫혀 있다. 다음 레버 = Anthropic 키(유료·네이티브 tool) 또는 Spring AI 1.1 `google-genai` SDK(무료지만 Boot 3.5 상향 리스크).
- [ ] **골든셋 각주 3건** — u13 오채점 가능성 / B2 `hard` 버킷 오라벨 / 멀티홉 `m3` factsB가 정답과 그 부정을 구분 못 함. **재측정 없이 각주로만 기록됨**(연우님 판정 2026-07-14). 멀티홉을 다시 돌린다면 `m3` factsB를 `["이월되지 않","소멸"]`로 고치는 게 선결.
- [ ] ~~저비용 배포~~ — **하지 않기로 결정**(2026-07-16).

### D. 잔여 정리

- [ ] `C:\Users\SAMSUNG\yeonwoo-dev` — 내용은 전부 삭제됐고 **빈 폴더가 잠겨서** 안 지워진다. 재부팅하면 사라진다. (personal이 진짜 작업 사본이므로 없어도 무방.)

## 6. 규칙 (커밋할 때)

- 커밋 신원: 개인 계정 `Yeonwoo Kim <ppupy1200@gmail.com>` 고정 (이 레포에 로컬 config 설정돼 있음)
- 커밋 메시지: `feat: ...`, `fix: ...` 처럼 영문 타입 + 한글 설명
- AI 공동저자 표기(Co-Authored-By) 넣지 않음

## 7. 작업 방식 (모든 세션 공통) ⭐

1. **직접 구현 구간은 step-by-step으로.** ROADMAP의 "과제 (직접 구현)" 구간에서 AI는 코드를 한꺼번에 만들지 않는다.
   한 번에 한 단계씩: ① 이번 단계의 목표와 왜 필요한지 → ② 파일 위치와 타이핑할 코드(작은 단위) →
   ③ 동작/컴파일 확인 방법 → 연우님이 직접 치고 확인한 뒤 다음 단계로. "왜?"라는 질문에는 코드보다 개념을 먼저 설명한다.
2. **진척·수정이 생길 때마다 이 파일 §3 진행 로그에 즉시 기록하고 커밋한다.** 여러 세션·여러 PC에서 이어지는
   프로젝트라 이 로그가 유일한 이어가기 기준이다. "나중에 몰아서"는 금지.
3. **포트폴리오 글감은 그때그때 `docs/design-notes.md`에 축적한다.** 설계 판단(선택지 비교·채택 근거),
   측정 결과(before→after·측정 환경·재현 방법)가 생기면 바로 기록 — 나중에 포트폴리오 사이트(`yeonwoo-dev`)
   케이스 스터디의 원고가 된다.
