# ask-wiki — Claude Code 프로젝트 가이드

사내 문서를 근거로 답하는 위키 챗봇(RAG). 모던 Java 21 + Spring Boot 3.3, 로컬 LLM(Ollama).
이 파일은 이 레포에서 Claude Code(및 사람)가 따르는 프로젝트 규칙이다. 상세 맥락은 아래 문서를 먼저 읽는다.

## 먼저 읽을 문서 (이어가기 기준)

- `docs/START-HERE.md` — §3 진행 로그의 "지금 여기"부터 이어간다.
- `docs/ROADMAP.md` — Phase별 단계·측정 계획.
- `docs/design-notes.md` — 설계 판단·측정 결과(포트폴리오 원고).
- `docs/b2-prompt-experiments.md` — RAG 프롬프트 실험 로그.

## 빌드·테스트

호스트에 JDK 21이 있으면(예: `~/.jdks/temurin-21.0.5`) Gradle로, 없으면 Docker로 빌드한다.

- 전체 테스트(단위/통합, Ollama 불필요): `./gradlew test`
  - Testcontainers가 MySQL 8.4를 자동 기동한다. **Docker Desktop 필요.**
  - 이 PC는 Docker Engine 최소 API 이슈로 `build.gradle`에 `api.version=1.44`를 고정해 뒀다. IntelliJ에서 돌릴 땐 "Run tests using: Gradle".
- 품질 평가(`@Tag("eval")`, 실제 Ollama 필요): `./gradlew evalTest`
  - 임베딩 `nomic-embed-text`, 챗 `llama3.2:3b` 모델이 `localhost:11434`에 있어야 한다.
  - 기본 `test`에서는 eval 태그가 제외된다(CI에 결정적 지표만 남기기 위함).
- 앱 전체 실행: `docker compose up --build -d` (포트 충돌 시 `.env`로 오버라이드).

## 컨벤션

- 모든 요청/응답 DTO는 Java `record`. 결과 분기는 `sealed interface` + switch 패턴 매칭(`RagResult` 등).
- 패키지 구조·API 계약·데이터 모델은 `docs/DESIGN.md`(SSOT)를 따른다. 벗어나야 하면 문서를 먼저 고친다.
- 커밋 신원: 개인 계정 `Yeonwoo Kim <ppupy1200@gmail.com>` 고정(레포 로컬 config). AI 공동저자 표기 없음.
- 커밋 메시지: `feat:`/`fix:`/`test:`/`docs:` 등 영문 타입 + 한글 설명.
- 진척·판단·측정은 그때그때 `docs/START-HERE.md` 진행 로그와 `docs/design-notes.md`에 기록한다.

## 커밋 전 검사 (금칙어 스캐너)

공개 레포라 민감어가 새어나가지 않도록 커밋 전 자동 검사를 둔다.

- git 훅: `.githooks/pre-commit`이 스테이지된 **추가된 줄**만 `.githooks/denylist.txt`와 대조해 걸리면 커밋을 막는다.
- 설치(1회): `git config core.hooksPath .githooks`
- 금칙어 관리: `.githooks/denylist.txt`(한 줄에 하나). 이 파일 자신과 기존 커밋 내용은 검사하지 않는다.
- 의도된 예외: `git commit --no-verify`.
- Claude Code 세션에서도 커밋 전 같은 검사를 수행한다(중복 안전망).

## AI 페어 워크플로우 (이 레포의 개발 방식)

- 설계·측정·문서는 Claude Code가, 구현은 Codex 서브에이전트에 위임하고 Claude가 검증한다.
- 딥다이브는 step-by-step: 목표·이유 → 코드 → 확인 방법을 한 단계씩. 상세는 `docs/START-HERE.md` §7.
