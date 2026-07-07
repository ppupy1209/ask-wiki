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
- ⬜ **Phase B2 - 답변 품질 평가 하네스**: 계획은 **`docs/ROADMAP.md`** 참고. **진행은 §7 작업 방식(step-by-step) 필수.**
- ⬜ **Phase C1~C3 - 채용 공고 대응 딥다이브 (2026-07-07 편성)**: C1 벡터 DB 이행(B1 패턴 이식·B2 검증) → C2 상용 LLM 스위치(토큰·비용 관측, 구 B4·배포 흡수) → C3 에이전틱 RAG. 근거·매핑은 ROADMAP "채용 공고 대응" 참조.
- 🔁 **W - Claude Code 워크플로우 아티팩트화 (병행, 2026-07-07 시작 결정)**: W-1 CLAUDE.md+금칙어 훅부터. 체크리스트는 ROADMAP.
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
