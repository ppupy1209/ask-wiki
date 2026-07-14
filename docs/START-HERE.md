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
        - **지금 여기 (2026-07-07)**: B1 완료. ⚠️ 3-7 커밋은 **미커밋 상태로 작업 트리에**(연우님 커밋·푸시 예정, `c5dbf76`(3-6)도 미푸시). 다음 Phase = **B2 답변 품질 평가 하네스**(ROADMAP). W-1(CLAUDE.md·금칙어 훅)도 병행 가능.
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
    - **🔧 C3 착수 (2026-07-14)**: **프레임워크 = Spring AI Tool Calling 확정**(연우님, LangChain4j 기각 — ROADMAP "Phase C3" 신설). 3요소(에이전틱 루프·Structured Output 분류·ChatMemory 멀티턴). **지금 여기 = C3-1 설계**. 정직한 전제: 강한 모델(Gemini) 필요 + 단일홉 코퍼스엔 이득 안 보일 수 있어 멀티홉 golden 확장 필요.
    - **✅ C3-1 구현 (2026-07-14)**: `WikiSearchTool`(per-call 도구·소스 수집기, 전역 빈 아님)·`AgenticRagService`(ChatClient 도구 루프 → RagResult, 단발 `RagService` 유지). 결정적 단위테스트 5종(fallback — 목 ChatModel은 Spring AI 내부 tool 루프를 못 돌려서 도구·매핑을 분리 테스트). **전체 스위트 44 그린**(컨텍스트 부팅 OK, C4식 순환 없음). Gemini tool-calling 선검증 통과. Codex 위임·Claude 검증.
    - **✅ C3-1 측정 완료 (2026-07-14) = 정직한 한계**: 멀티홉 골든 6문항으로 단발 vs 에이전틱 비교. **발견 1** 강한 모델 경로 막힘 — Gemini(OpenAI-compat)는 다중턴 tool에서 `thought_signature` 미echo로 **HTTP 400**(단일턴 RAG는 완벽, C2). **발견 2** 약한 모델(Ollama 3B)은 에러 없이 돌지만 **멀티홉 이득 없음**(단발 2/6 vs 에이전틱 1/6 @topK4, 둘 다 0/6 @topK2·더 느림 — 3B가 재검색을 제대로 안 씀). **결론**: 에이전틱 값어치는 강하고 네이티브 tool-calling 하는 모델이 있어야 드러남 → 현 무료/로컬 스택으론 미증명(B2-5 계열 정직한 부정). 하네스 `MultiHopComparisonEvalTest`+`eval/questions-multihop.json`. 상세 design-notes §7. **C3-1 마감.** 다음 레버(강한 네이티브-tool 모델: Claude 키 또는 Spring AI 1.1)는 유보, 연우님이 **C3-2(질문 분류/라우팅)를 다음 세션으로 선택**(2026-07-14).
    - **🔧 C3-2 착수 — 설계 확정·구현 위임 (2026-07-14)**: 질문 분류/라우팅. **핵심: 단일턴 Structured Output(`ChatClient.entity()`)이라 C3-1의 다중턴 tool 벽(Gemini `thought_signature`)을 피해 무료 Gemini로 실측 가능** — 추상화의 한계 지점(단일턴 OK/다중턴 X)을 두 스텝이 가른다. 설계(연우님 확정): 신설 `routing` 패키지(`QuestionType`{WIKI·CHITCHAT·AMBIGUOUS}·`QuestionRoute(type,message)`·`QuestionRouter.classify`) → **`AskService` seam에 라우팅**(C4가 뽑은 오케스트레이션이라 `/api/ask`·MCP `ask_wiki` 자동 적용): CHITCHAT→`Answered(message,[])`·AMBIGUOUS→**신설 `RagResult.Clarify(message)`**·WIKI→기존 RAG 낙하. 결정 3: ① 되묻기·즉답 message는 **분류 호출이 함께 생성**(1콜) ② `askwiki.routing.enabled` **기본 off**(현 동작·44 그린 불변 — default-on이면 결정적 스위트가 Ollama 분류를 요구) ③ classify 실패 **fail-open→WIKI**. 분류 정확도 측정(golden set·eval)은 **C3-4로 분리**(범위 = 코어만, 연우님 확정). 구현 Codex 위임·Claude 검증. **지금 여기 = C3-2 Codex 산출물 검증 대기.**
    - **🔧 C1 착수 (2026-07-12)**: ROADMAP에 "Phase C1" 섹션 신설 — 문제 정량화(20k=60MB·25ms지만 O(N) 선형 + **멀티 인스턴스 정합성 결함 발견**: relay가 PROCESSED 마킹하면 다른 인스턴스는 그 이벤트를 못 봐 사본이 어긋남 = 단일 인스턴스 전제), 후보 5종 비교표(ES kNN·Qdrant·pgvector·Redis+RediSearch·유지), 벡터 스토어 스타터 4종 **1.0.9 존재 검증 완료**. Step 1 ✅ 결정(2026-07-12, 연우님): **ES kNN + 자체 포트(`VectorIndex`)+어댑터** — 채택·기각 근거 design-notes §5.
    - **⚙️ 진행 방식 변경 (2026-07-12, 연우님 지시)**: C1 구현은 §7 step-by-step(직접 타이핑) 대신 **Codex 위임·Claude 검증**으로 전환. (W-3 사례 축적)
    - **✅ Step 2-1 완료 (2026-07-12)**: `VectorIndex` 포트 추출(동작 불변) — Codex 작성, Claude 검증(diff 리뷰 + 전체 19 테스트 그린) 후 커밋 `635431b`. Codex 샌드박스가 gradle 소켓/락을 막아 **테스트 실행은 Claude 몫**(B1 3-7과 같은 제약 패턴).
    - **✅ Step 2-2 완료 (2026-07-12)**: ES 인프라 — compose `elasticsearch:8.17.4`(single-node·보안off·512m힙·es-data 볼륨·`ES_PORT` 오버라이드), `elasticsearch-java`(Boot BOM 관리)+`testcontainers:elasticsearch`, lazy `ElasticsearchClient` 빈, `EsConnectivityTest`(Testcontainers 스모크). **Spring AI VectorStore 스타터 미사용 결정**(add()가 스토어에서 재임베딩하는 설계 — 우리는 임베딩을 MySQL에 보유, design-notes §5). Codex 작성·Claude 검증(diff+전 스위트 그린) `a7694e1`. 위임 중 함정: 서브에이전트 2분 타임아웃이 Codex 프로세스를 함께 죽임(파일 반영은 완료된 뒤였음) → **이후 위임은 --background 모드**로. **✅ Step 2-3 완료 (2026-07-12)**: `EsVectorIndex`(_id=chunkId 멱등 upsert·kNN(num_candidates≥100)·**점수 역변환 2s−1로 InMemory와 동일 [-1,1] 스케일**·deleteByQuery·bulk rebuild·refresh-on-write 프로퍼티) + `VectorIndexConfig`(@Primary 선택, `askwiki.vector-index.impl`=memory|elasticsearch) + InMemory 시작 rebuild를 memory 모드일 때만 + 계약 테스트 4종(멱등 add·랭킹/스케일·범위 한정 삭제·rebuild). Codex 작성(--background 위임)·Claude 검증 — **컴파일 에러 1건(닫는 괄호 누락) Claude가 수정**(Codex 샌드박스가 컴파일 불가라 남는 슬립 유형), 전 스위트 10클래스 그린. **✅ Step 2-4 완료 → Step 2 전체 마감 (2026-07-12)**: `EsOutboxIntegrationTest` 4종 — 롤백 유령 0·relay 반영+재폴링 멱등·크래시 중간 add의 부분 색인이 _id upsert로 중복 없이 회복(무유실)·삭제 outbox 통합. Codex 작성·Claude 검증(수정 1건: **목 벡터를 768차원으로** — ES는 dense_vector dims를 강제, InMemory엔 없는 제약). 전 스위트 11클래스 그린, `66d9908`. **✅ Step 3-① hit rate 동등성 실측 (2026-07-12)**: 평가 러너 3종을 `VectorIndex` 포트 주입으로 전환(env `VECTOR_INDEX_IMPL`로 A/B) 후 — memory **93.3%@4**(B2와 완전 일치) vs ES **90.0%@4**(@1·@2·@8 완전 동일, 1문항 4↔5위 스왑 — **근사 아님**, num_candidates≥N 완전탐색·부동소수 코사인 경로 차. design-notes §5). 측정 조건: compose ES + `ASKWIKI_ES_REFRESH_ON_WRITE=true`.
    - **✅ Step 3 전체 완료 → C1 완료 (2026-07-12)**: 측정 5종 — ① hit rate 동등성 93.3 vs 90.0@4(부동소수 경로차, 근사 아님) · ② 20k 스케일 벤치(지연 역전 22.8→12.8ms, recall 0.83@nc100·dial, `rebuild()` OOM 발견→`BULK_BATCH_SIZE=500` 배치 수정) · ③ 반영 지연(ES refresh 계층 127→879ms, refresh-off 기본) · ④ 기동(ES 재시작 재빌드 0 vs 인메모리 6.3s) · ⑤ ETL(Chunker=TokenTextSplitter 8/8 수렴, 짧은 문서 → Chunker 유지). 하네스: `VectorIndexScaleBenchTest`·`EsReflectionLatencyTest`·`EtlComparisonEvalTest`(전부 `@Tag("eval")`). 상세 design-notes §5.
    - **2026-07-11 인프런 Spring AI 강의(JSCODE) 접목 확정** (3건 모두 연우님 승인): **P1** C1 보강 — ES 후보를 강의 실습으로 검증 + Spring AI `VectorStore` 인터페이스·ETL vs 자체 Chunker 설계 결정 추가 / **P2** C3 구체화 — Spring AI Tool Calling 우선·Structured Output 질문 분류·ChatMemory 멀티턴(+B2 멀티턴 golden set) / **P3** C4 신설 — MCP 서버(`spring-ai-starter-mcp-server-webmvc`, Boot 3.3+·1.0.x 포함 확인 — 버전 상향 불필요). **남은 순서 = C2-5 → C1 → C4 → C3.** 강의 섹션 2~5·7은 이미 초과 달성이라 접목 제외(입문 강의 — 딥다이브 차별화는 계속 B2 하네스 증명으로). 상세는 ROADMAP "강의 접목 메모"·각 Phase 불릿.
- 🔁 **W - Claude Code 워크플로우 아티팩트화 (병행, 2026-07-07)**: ✅ W-1 완료(프로젝트 CLAUDE.md + 커밋 가드 훅 2겹: git pre-commit + Claude Code PreToolUse, secret-scanner 방식). 남음: W-2 커스텀 커맨드 / W-3 서브에이전트 사례 기록 / W-4 워크플로우 글. 체크리스트는 ROADMAP.
- ⬜ **Phase D - 포트폴리오 통합**: study 글 + 다이어그램을 포트폴리오 사이트(`yeonwoo-dev/web/`)에 반영. 체크리스트는 `docs/ROADMAP.md` 하단.

> 이 로그를 매 Phase 끝날 때 갱신한다(체크박스 ✅). 새 PC/세션은 이 로그의 "지금 여기"부터 이어가면 된다.

## 4. 실행 방법 (필요한 건 Docker 뿐, 자바 설치 불필요)

```bash
docker compose up --build -d
# 최초 1회만 AI 모델 다운로드 (약 2GB, 몇 분)
docker compose exec ollama ollama pull nomic-embed-text
docker compose exec ollama ollama pull llama3.2:3b
# 확인
curl http://localhost:8080/actuator/health      # {"status":"UP"} 나오면 성공
```
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
