# 여기서 시작하세요 (이어서 하기 가이드)

> 이 파일은 **사람도, AI(Claude/Codex)도** 이 프로젝트를 처음부터/중간부터 이어갈 수 있게 하는 안내서입니다.
> 다른 PC나 새 대화에서 시작할 땐 이 파일 → `README.md` → `docs/DESIGN.md` 순으로 읽으세요.

---

## 1. 이 프로젝트가 뭔가요? (한 문장)

**"내 문서(회사 규정·매뉴얼 등)를 올려두면, 자연어로 질문했을 때 그 문서를 근거로 답해주는 챗봇 API"** 입니다.
이런 방식을 **RAG**(Retrieval-Augmented Generation, 검색해서 답을 생성)라고 부릅니다.

왜 만드나: "그냥 Java 개발자"가 아니라 **"현대적이고 확장 가능한 시스템 + AI + 성능"을 다룰 줄 안다**는 걸
면접관이 직접 실행해보며 확인할 수 있는 포트폴리오를 만들기 위해서입니다.

## 2. 이 하나의 프로젝트로 보여주는 4가지 (study)

| # | 무엇을 | 쉽게 말하면 |
|---|---|---|
| ① | Virtual Threads | Java 21 신기능으로 "동시 접속자를 싸게 많이 처리"하는 걸 측정으로 증명 |
| ② | Spring AI + RAG | Java로 AI(로컬 LLM)를 붙여 문서 기반 답변을 만드는 것 |
| ③ | 검색 최적화 | 문서가 많아져도 "비슷한 문서 찾기"를 빠르게 |
| ④ | 캐싱 + 관측 | 같은 질문은 기억해서 빨리 답하고, 성능을 그래프로 기록 |

## 3. 진행 로그 (Phase별) — 이어가는 기준점 ⭐

전체 흐름: **Phase 1**(실행 확인) → **2**(RAG 구현) → **3**(Virtual Threads 측정) → **4**(숫자 정리) → **5**(포트폴리오 통합)

- ✅ **Phase 0 — 골격**: Claude+Codex가 전체 스캐폴딩. Docker 빌드·기동·Flyway·health 검증 완료.
- ✅ **Phase 1 — 실행 확인**: `docker compose up` → 문서 업로드/조회 동작 확인. (문서 올리면 Ollama가 임베딩해 MySQL 저장)
- ✅ **Phase 2 — RAG 구현 (study ②)**: `rag/RagService.answer()` 완성. 질문→임베딩→검색→프롬프트→LLM→답변+근거. `/api/ask`가 근거 기반 답변 반환. 핵심 학습: **프롬프트 한 줄이 답을 좌우(환각 억제)**.
- ⬜ **Phase 3 — Virtual Threads 측정 (study ①)** ← **지금 여기**
    - `spring.threads.virtual.enabled` 토글 on/off로 부하(k6)를 걸어 처리량·p99 비교
    - 노트: `docs/LEARNING-virtual-threads.md`, 부하 스크립트: `bench/ask-load.js`
- ⬜ **Phase 4 — 숫자 정리**: 위 측정치를 `README.md`의 `___ → ___` 자리에 기입
- ⬜ **Phase 5 — 포트폴리오 통합 (Claude 담당)**: study 4건 글 + 다이어그램을 포트폴리오 사이트에 반영

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
2. 아무 폴더에서 `git clone https://github.com/ppupy1209/rac-doc.git`
   (※ 이 레포는 독립 프로젝트입니다. 다른 곳에 넣을 필요 없이 그냥 클론해서 열면 됩니다.)
3. 위 4번 실행 방법대로 실행
4. 작업 후 `git add -A && git commit && git push` 로 저장 → 어느 PC에서든 최신 상태로 이어짐
   (`.env`는 개인 설정이라 공유 안 됨. 새 PC에서 필요하면 다시 만들면 됨)

**새 AI 대화(Claude/Codex)에서 이어시킬 때 — 이렇게 말하세요:**
> "rac-doc 레포에서 이어서 작업할 거야. `docs/START-HERE.md`, `docs/DESIGN.md`,
>  `docs/LEARNING-rag.md`, `docs/LEARNING-virtual-threads.md`를 먼저 읽고 현재 상태 파악해줘."

## 6. 규칙 (커밋할 때)

- 커밋 신원: 개인 계정 `Yeonwoo Kim <ppupy1200@gmail.com>` (회사 계정 금지)
- 커밋 메시지: `feat: ...`, `fix: ...` 처럼 영문 타입 + 한글 설명
- AI 공동저자 표기(Co-Authored-By) 넣지 않음
