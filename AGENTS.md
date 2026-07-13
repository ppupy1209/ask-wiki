# ask-wiki — AI 에이전트 공유 문서

> 이 파일은 AI 에이전트(Claude Code·Codex)용 **빠른 진입점**이다.
> 진행 상태·판단·측정의 **정본(SSOT)은 `docs/`** 다 — 아래 "먼저 읽을 문서"를 반드시 따른다.
> 이 파일과 `docs/`가 어긋나면 `docs/`가 이긴다.

## 먼저 읽을 문서 (이어가기 순서)

1. `docs/START-HERE.md` — §3 "진행 로그"의 **"지금 여기"**부터 이어간다. (진행 상태 정본)
2. `docs/ROADMAP.md` — Phase별 단계·측정 계획.
3. `docs/design-notes.md` — 설계 판단·측정 결과(포트폴리오 원고).
4. `docs/DESIGN.md` — 패키지 구조·API 계약·데이터 모델(계약 정본).

## 개요 / 목표

사내 문서를 근거로 답하는 위키 챗봇(RAG). "그냥 Java 개발자"가 아니라 **현대적·확장 가능한 시스템 + AI + 성능**을
측정으로 증명하는 포트폴리오. 각 Phase는 단순 구현이 아니라 **재현 테스트·측정·판단 기록을 남기는 딥다이브**다.

## 기술 스택

- **백엔드**: Java 21, Spring Boot 3.3.5, Spring AI 1.0.9(BOM), Gradle wrapper.
- **데이터/검색**: MySQL 8.4(문서·청크·임베딩 JSON·outbox), Elasticsearch 8.17.4(벡터 인덱스 kNN, C1), Redis(질의 캐시).
- **LLM**: Ollama(임베딩 `nomic-embed-text` 고정 / 챗 `llama3.2:3b`) ↔ Gemini(`gemini-3.1-flash-lite`, OpenAI-compat) 스위치(`askwiki.llm.provider`).
- **프론트**: 정적 웹 챗 UI(`src/main/resources/static`, `templates`).
- **인프라/관측**: Docker Compose, Micrometer → Prometheus → Grafana.

## 실행·테스트 명령

- 단위/통합 테스트(Ollama 불필요, Testcontainers가 MySQL/ES 기동 — **Docker Desktop 필요**): `./gradlew test`
- 품질 평가(`@Tag("eval")`, 실제 Ollama 필요, `localhost:11434`): `./gradlew evalTest`
- 앱 전체 실행: `docker compose up --build -d` (포트 충돌 시 `.env`로 오버라이드)
- 이 PC 제약: `build.gradle`에 `api.version=1.44`·Testcontainers 1.21.3 고정(Docker Engine 최소 API 이슈). IntelliJ는 "Run tests using: Gradle".

## 아키텍처 결정 로그 (요약 — 상세는 `docs/design-notes.md`)

- [B1] 벡터 인덱스 반영을 DB 커밋과 **Transactional Outbox + 폴링 relay**로 원자화(유령 엔트리 0). 멱등 2겹(상태 필터 + chunkId 멱등 add).
- [C2] 챗 LLM만 프로바이더 스위치(임베딩 nomic 고정 = 실험 통제). 상용 모델이 로컬 소형 모델의 환각 바닥을 돌파함을 B2 하네스로 증명.
- [C1] 인메모리 벡터 인덱스 → **ES kNn 이행**. Spring AI `VectorStore`를 도메인에 직접 들이지 않고 **자체 포트 `VectorIndex` + 어댑터**(정합성 계약을 시그니처로 명시).
- [C4] 검색·질의를 **MCP 서버**(`spring-ai-starter-mcp-server-webmvc`, WebMVC SSE)의 `search_wiki`·`ask_wiki` 툴로 노출. `ask/AskService` 추출로 `/api/ask`와 캐시+RAG 경로 공유. 챗 모델 tool-resolver와의 기동 순환은 `WikiMcpTools`의 AskService `@Lazy` 주입으로 절단.

## 컨벤션

- 모든 요청/응답 DTO는 Java `record`. 결과 분기는 `sealed interface` + switch 패턴 매칭(`RagResult`).
- 패키지 구조·API 계약·데이터 모델은 `docs/DESIGN.md`를 따른다. 벗어나야 하면 문서를 먼저 고친다.
- 커밋: 개인 계정 `Yeonwoo Kim <ppupy1200@gmail.com>` 고정, AI 공동저자 표기 없음. 메시지 = 영문 타입(`feat:`/`fix:`/`test:`/`docs:`) + 한글 설명.
- 커밋 전 금칙어 스캐너(`.githooks/pre-commit` + `.claude` PreToolUse 훅). 공개 레포이므로 민감어·식별 정보가 새어나가지 않게 한다(금칙어 목록은 `.githooks/denylist.txt`).

## AI 페어 워크플로우

- 설계·측정·문서는 **Claude Code**가, 핵심 구현은 **Codex 서브에이전트**에 위임하고 Claude가 검증한다.
- 위임은 최대 2회 재위임까지. Codex 샌드박스가 gradle 소켓/락을 막으므로 **테스트 실행은 Claude 몫**(B1·C1에서 반복된 제약).

## 현재 진행 상태 (정본은 `docs/START-HERE.md` §3)

- **완료**: Phase 0·1·2·3·3b, A·A-2, B1(정합성), B2(품질 하네스), C2(프로바이더 스위치), C1(ES 벡터 DB 이행), **C4(MCP 서버 — 전체 스위트 39/39 그린, 2026-07-13)**. W-1(CLAUDE.md·커밋 가드 훅).
- **지금 여기 / 다음 작업**: C4 구현 완료. 남음: (선택) 라이브 MCP 데모(스택+Ollama, `/sse` tools/list에 `search_wiki`·`ask_wiki` 노출 확인 = W-3/W-4 글감) + 커밋(연우님). 다음 Phase = **C3 에이전틱 RAG**.
- **이후**: C3(에이전틱 RAG), W-2~4(워크플로우 아티팩트), D(포트폴리오 통합).
