# Ask Wiki

**사내 문서를 넣으면, 그 문서를 근거로 답하는 위키 챗봇.** 회사 규정·매뉴얼·온보딩 문서를 올려두면 직원들의 자연어 질문에 근거(출처)와 함께 답합니다. 로컬 LLM(Ollama) 기반 RAG로 동작하며, 모던 Java 21 + Spring Boot 3.3 백엔드입니다.

> 문서를 올리면 청킹→임베딩→MySQL 저장하고, 질문하면 유사 문서를 근거로 LLM이 답합니다.
> **호스트에 Java 설치가 필요 없습니다.** Docker만 있으면 `docker compose up`으로 전부 실행되고, `http://localhost:8080`에서 웹 UI로 바로 써볼 수 있습니다.

---

## 왜 만들었나

사내 문서는 흩어져 있고, 답을 아는 사람은 바쁩니다. 문서를 한 곳에 넣어두면 누구나 질문으로 꺼내 쓸 수 있는 위키 챗봇을 지향합니다. 동시에 이 서비스는 실무에서 중요한 기술 결정(동시성·AI·검색 성능·캐싱/관측성)을 **직접 도입하고 정량 지표로 검증**하며 만드는 프로젝트이기도 합니다 — 아래 각 study가 그 실험 기록입니다.

- **Java 21 Virtual Threads**: 동시성 처리량을 부하 테스트로 측정
- **Spring AI + 로컬 LLM(Ollama) 기반 RAG**: Java로 AI 파이프라인 구성
- **MySQL 벡터 검색**: 데이터 증가에 따른 조회 성능 최적화
- **Redis 분산 캐시**: 반복 질의의 비용·지연 절감 (앱 재시작·다중 인스턴스에도 유지)
- **Prometheus · Grafana · k6**: 부하를 걸고 시스템을 실시간으로 관측·기록

즉 "돌아가기만 하는 것"이 목표가 아니라, **각 기술을 왜·언제 쓰는지 이해하고 어떤 수치로 개선되는지 스스로 측정해 기록**하는 것을 지향합니다.

다음 단계(증분 인덱싱 정합성, 답변 품질 평가 하네스)는 [`docs/ROADMAP.md`](docs/ROADMAP.md)에 있습니다.

---

## 이 프로젝트가 보여주는 것

| # | 주제 | 핵심 질문 | 검증 지표 |
|---|---|---|---|
| ① | **Virtual Threads** (Java 21) | I/O 대기가 많은 API에서 플랫폼 스레드풀 대비 처리량이 얼마나 오르나? | 동시 사용자 처리량 · p99 |
| ② | **Spring AI + RAG** | Java로 로컬 LLM 기반 RAG를 어떻게 구성하나? 환각은 어떻게 억제하나? | 근거 인용 · 컨텍스트 밖 질문 거절 |
| ③ | **MySQL 벡터 검색 최적화** | 임베딩 유사도 검색의 O(N) 전수 스캔을 어떻게 줄이나? | 검색 응답시간 (before→after) |
| ④ | **캐싱 + 관측성** | 반복 질의 비용/지연을 어떻게 줄이고, 성능을 어떻게 기록하나? | 응답시간·LLM 호출 절감 · Grafana |

각 주제의 상세 설계·검증은 [`docs/`](docs/) 참고.

---

## 아키텍처

```mermaid
flowchart LR
    C[Client] -->|POST /api/documents| D[DocumentController]
    C -->|POST /api/ask| A[AskController]

    D --> DS[DocumentService 청킹]
    DS --> E[EmbeddingClient +Caffeine]
    DS --> DB[(MySQL 8.4 document/chunk)]

    A --> QC[QueryCache]
    QC --> RD[(Redis)]
    A --> R[RagService]
    R --> E
    R --> S[SearchService cosine 검색]
    S --> DB
    R --> OL2

    E -->|embeddings| OL[Ollama nomic-embed-text]
    R -->|chat| OL2[Ollama llama3.2:3b]

    APP[Spring Boot app] -.->|/actuator/prometheus| P[Prometheus] --> G[Grafana]
```

- **동기 흐름**: `ask` → 캐시 확인 → (miss 시) 질문 임베딩 → 유사 청크 검색 → 프롬프트 조립 → LLM 답변 → 캐시 저장
- **관측**: 앱이 Micrometer로 지표 노출 → Prometheus 수집 → Grafana 대시보드

---

## 기술 스택

- **Java 21** (Record · sealed interface + switch 패턴 매칭 · Virtual Threads), **Spring Boot 3.3.5**
- **Spring AI 1.0.9** — 챗 LLM은 **프로바이더 스위치**(`ASKWIKI_LLM_PROVIDER=ollama|gemini`), 임베딩은 Ollama 고정
- **MySQL 8.4** (Flyway 마이그레이션) — 문서·청크·임베딩 원본 저장 / **Elasticsearch 8.17 kNN** — 벡터 검색(`VECTOR_INDEX_IMPL=elasticsearch`, 기본은 인메모리 인덱스)
- **LLM**: 임베딩 `nomic-embed-text`(로컬·무료 고정) · 챗 `llama3.2:3b`(기본·키 불필요) 또는 `gemini-3.1-flash-lite`(무료 키·**환각 0% 실측**)
- **Redis** (질의 응답 분산 캐시), **Caffeine** (임베딩 인스턴스 로컬 메모이제이션)
- **관측성**: Micrometer · Prometheus · Grafana / **부하**: k6
- **실행**: Docker Compose (앱 컴파일도 컨테이너의 Java 21이 담당하므로 호스트 JDK 불필요)

---

## 빠른 실행 (면접관용, 필요한 것은 Docker뿐)

> **먼저 읽어주세요 — 챗 모델을 무엇으로 두느냐가 품질을 가릅니다.**
> 이 레포는 골든셋으로 두 경로를 **직접 측정**했고, 숨기지 않고 그대로 적습니다.
>
> | | **A. 무료 Gemini 키 (권장)** | **B. 로컬 Ollama만 (키 0)** |
> |---|---|---|
> | 환각률 (문서에 없는 질문에 지어냄) | **0%** | **75%** |
> | 질문 분류 정확도 | 97.2% | 41.7% *(전부 검색으로 찍는 축퇴)* |
> | 멀티턴 follow-up 재작성 | 정상 | 환각·언어 혼입 |
> | 응답 지연 (평균) | **0.6s** | 8.8s |
> | 준비물 | 무료 키 1개 (카드 불필요) | 없음 |
>
> B는 **키 없이 5분 만에 돌려보는 용도**로는 충분하지만, 답변 품질로 이 프로젝트를 판단하시려면 A로 봐주세요.
> 측정 방법·조건·해석은 [`docs/design-notes.md`](docs/design-notes.md) §4(프로바이더 비교)·§7(분류·재작성)에 있습니다.
> 프롬프트를 4종까지 바꿔봐도 로컬 3B의 환각 바닥은 안 내려갔고(60~75%), **모델을 바꾸자 0%가 됐습니다** — 그 실험 기록이 이 프로젝트의 핵심 중 하나입니다.

```bash
git clone https://github.com/ppupy1209/ask-wiki.git
cd ask-wiki

# 1) 임베딩 모델은 어느 경로든 필요 (약 270MB)
docker compose up -d ollama
docker compose exec ollama ollama pull nomic-embed-text
```

**A. 무료 Gemini 키로 (권장 — 환각 0%)**

```bash
# https://aistudio.google.com/apikey 에서 무료 키 발급 (카드 등록 불필요)
cp .env.example .env
#  .env 에서 두 줄만 수정:
#    ASKWIKI_LLM_PROVIDER=gemini
#    GOOGLE_GENAI_API_KEY=발급받은_키

docker compose up --build -d
curl http://localhost:8080/actuator/health      # {"status":"UP"}
```

**B. 키 없이 로컬만 (환각 75% — 구조 확인용)**

```bash
docker compose exec ollama ollama pull llama3.2:3b   # 챗 모델 추가 (약 2GB)
docker compose up --build -d
curl http://localhost:8080/actuator/health
```

| 서비스 | URL |
|---|---|
| 웹 UI (문서 업로드·질문) | http://localhost:8080 |
| API | http://localhost:8080/api |
| Grafana | http://localhost:3000 (익명 Admin) |
| Prometheus | http://localhost:9090 |

종료: `docker compose down` (데이터까지 초기화: `docker compose down -v`)

> **포트 충돌 시**: 로컬에 이미 MySQL(3306)·Grafana(3000) 등이 떠 있으면 `.env.example`을 `.env`로 복사해 호스트 포트만 바꾸세요(컨테이너 내부는 그대로). 예: `MYSQL_PORT=13306`, `GRAFANA_PORT=3001`.

### API 사용 예시

```bash
# 문서 등록 (텍스트)
curl -X POST http://localhost:8080/api/documents \
  -H "Content-Type: application/json" \
  -d '{"title":"휴가 규정","content":"연차 휴가는 입사 1년 후 15일이 부여된다. ..."}'

# 문서 파일 업로드 (md·txt·pdf)
curl -X POST http://localhost:8080/api/documents/upload -F "file=@휴가규정.pdf"

# 질문
curl -X POST http://localhost:8080/api/ask \
  -H "Content-Type: application/json" \
  -d '{"question":"연차는 며칠 나오나요?","topK":4}'
# -> { "answer": "...15일...", "sources": [...], "latencyMs": 000, "cached": false }
```

---

## study 상세

> 측정값은 로컬 하드웨어(특히 Ollama 성능)에 좌우되므로 **실측치**를 기입합니다. 방향성과 해석이 핵심입니다.

### ① Virtual Threads로 I/O 바운드 처리량 개선
실무의 **오케스트레이션(BFF/게이트웨이) 패턴**을 재현해 측정했다. `/api/bench/orchestrate`는 한 요청을 처리하려고 여러 다운스트림 서비스를 순차 호출하고 DB도 조회한다(인증 확인 → 문서 메타 조회(DB) → 관련 자료 보강 → 감사 로그). 각 단계가 실제 네트워크 I/O라 응답을 기다리는 동안 요청 처리 스레드가 묶인다. `spring.threads.virtual.enabled` 토글로 A/B 비교.
(다운스트림은 go-httpbin으로 흉내 낸다. 실제 `/api/ask`는 Ollama가 병목이라 스레드 효과가 가려지므로 제외했다. 더 단순한 비교용으로 `/api/bench/downstream`(단일 호출)과 `/api/bench/io`(순수 대기)도 있다.)

```bash
# 부하 비교 (k6, 설치 없이 Docker로 실행)
docker compose up -d --force-recreate app                                 # 가상 스레드 OFF (기본)
docker run --rm -i --network ask-wiki_default -e BASE=http://app:8080 grafana/k6 run - < bench/orchestrate-load.js

VTHREADS=true docker compose up -d --force-recreate app                   # 가상 스레드 ON
docker run --rm -i --network ask-wiki_default -e BASE=http://app:8080 grafana/k6 run - < bench/orchestrate-load.js
```

**측정 결과** (로컬, 500 동시 사용자 · 20초, 요청당 다운스트림 3회 × 120ms + DB 조회):

| 지표 | 플랫폼 스레드 (OFF) | 가상 스레드 (ON) |
|---|---|---|
| 처리량 | 538 req/s | **1,257 req/s** (약 2.3배) |
| 평균 응답 | 895 ms | **382 ms** |
| p99 응답 | 1.07 s | **902 ms** |

**해석:** 플랫폼 스레드는 기본 200개라 500명이 동시에 오면 나머지가 큐에서 대기하므로 처리량이 막히고 평균 응답이 895ms까지 부풀었다. 가상 스레드는 다운스트림 응답을 기다리는 동안 캐리어를 반납해 수백 개 요청을 동시에 처리하므로 처리량이 2.3배가 되고, 평균 응답은 큐 대기가 사라져 실제 작업시간(약 360ms)에 근접한다. **부하 중 JVM 라이브 스레드 수도 OFF ~350에서 ON ~220으로 감소**했는데, 이는 가상 스레드가 Tomcat 요청 처리 스레드를 걷어낸 결과다(남은 스레드는 다운스트림 HttpClient 자체 스레드풀). 상세 [`docs/LEARNING-virtual-threads.md`](docs/LEARNING-virtual-threads.md)

**부하 중 JVM 라이브 스레드 수** (Grafana, 500 동시 사용자):

| 가상 스레드 OFF (~350) | 가상 스레드 ON (~220) |
|:---:|:---:|
| ![가상 스레드 OFF 스레드 수](docs/images/vthreads-off-grafana.png) | ![가상 스레드 ON 스레드 수](docs/images/vthreads-on-grafana.png) |

같은 500명 부하인데 스레드 곡선이 확연히 다르다. 가상 스레드는 Tomcat 요청 처리 스레드를 걷어내 더 적은 스레드로 더 높은 처리량을 낸다.

### ② Spring AI + RAG 파이프라인
질문 임베딩 → 유사 청크 검색 → "컨텍스트만 근거로 답하라" 프롬프트 조립 → LLM. 문서에 없는 질문은 "모른다"고 답하도록 설계(환각 억제). 상세 [`docs/LEARNING-rag.md`](docs/LEARNING-rag.md)

### ③ MySQL 벡터 검색 최적화
baseline은 검색마다 전체 청크를 DB에서 읽고 임베딩 JSON을 파싱해 코사인을 계산하는 **O(N) 전수 스캔**이다([`SearchService`](src/main/java/com/yeonwoo/askwiki/search/SearchService.java)). 데이터가 늘수록 선형으로 느려진다. 최적화는 **인메모리 벡터 인덱스**([`InMemoryVectorIndex`](src/main/java/com/yeonwoo/askwiki/search/InMemoryVectorIndex.java))로, 임베딩을 앱 시작 시 한 번 메모리에 올려 정규화해 두고 검색은 메모리 내 내적으로 처리한다. 본문/제목은 상위 K건만 DB에서 가져온다.

**측정 결과** (청크 20,002개, 검색 평균):

| 방식 | 검색 응답 |
|---|---|
| baseline (DB 전수 스캔) | 6,458 ms |
| 인메모리 인덱스 | **25 ms** (약 256배 빠름) |

매 검색의 "DB 전체 읽기 + JSON 파싱"을 제거한 결과다. 실제 `/api/ask`도 이 인덱스를 사용하므로 20k 청크에서도 빠르게 답한다. `/api/bench/search?mode=dbscan|memory`로 두 방식을 직접 비교할 수 있다.

**트레이드오프**(정직하게 기록): 인메모리 인덱스는 (a) 임베딩 크기만큼 메모리를 쓰고 (b) 데이터가 바뀌면 갱신이 필요하다(문서 추가 시 증분 반영, 삭제 시 재빌드). 데이터가 아주 커지면 Redis 벡터 검색이나 전용 벡터 DB(ANN 인덱스)로 빼는 것이 다음 수순이다.

### ④ 캐싱 + 관측성
동일 질의를 **Redis 분산 캐시**([`QueryCache`](src/main/java/com/yeonwoo/askwiki/cache/QueryCache.java))로 단락시켜 임베딩·검색·LLM 호출을 통째로 건너뛴다. 캐시 히트/미스는 Micrometer로 `cache_gets_total{cache="query_cache"}` 지표를 내보내 Prometheus/Grafana로 관측한다.

**측정 결과** (같은 질문 2회 호출):

| | 응답시간(latencyMs) | LLM 호출 | cached |
|---|---|---|---|
| 1번째 (miss) | 8,139 ms | 1회 | false |
| 2번째 (hit) | **0 ms** | **0회** | true |

즉 반복 질의는 8초 LLM 호출을 **0ms·0회**로 줄인다(비용·부하 제거).

**왜 Caffeine이 아니라 Redis인가**: 인메모리 캐시는 앱을 재시작하면 사라지고 인스턴스마다 따로 논다. Redis는 외부 저장소라 재시작·다중 인스턴스에도 캐시가 유지·공유된다. 실제로 캐시된 질문을 남긴 채 앱을 재시작한 뒤 같은 질문을 하면 여전히 `cached:true`(약 25ms)로 응답한다.

**관측성 대시보드** ([`rag-observability.json`](grafana/provisioning/dashboards/rag-observability.json))는 부하 걸린 시스템을 한 화면에서 관측한다: 처리량(req/s), 응답 p95/p99, **JVM 라이브 스레드 수**, 힙 메모리, CPU, 캐시 히트/미스 6개 패널. `http://localhost:3001` 의 "RAG 서비스 관측 대시보드"에서 확인한다. 부하 테스트(k6, `bench/io-load.js`)를 걸며 지켜보면 처리량이 치솟고 가상 스레드 ON/OFF에 따라 스레드 수 곡선이 달라지는 것을 실시간으로 볼 수 있어 study ①의 결과가 그래프로 재현된다. (p99 백분위는 `management.metrics.distribution.percentiles-histogram`로 히스토그램 지표를 켜서 계산)

<!-- Grafana 대시보드 스크린샷을 docs/images 에 저장 후 아래 주석 해제 -->
<!-- ![Grafana 관측 대시보드](docs/images/grafana-dashboard.png) -->

---

## 프로젝트 구조

```
src/main/java/com/yeonwoo/askwiki/
├─ common/     record DTO, sealed RagResult, 예외 핸들러
├─ document/   문서 CRUD·청킹·JPA 엔티티
├─ embedding/  Ollama 임베딩 클라이언트(+Caffeine 캐시)
├─ search/     코사인 유사도 검색 (study ③)
├─ rag/        검색→프롬프트→LLM 오케스트레이션 (study ②)
├─ ask/        POST /api/ask, 동시성 핫스팟 (study ①)
├─ cache/      질의 캐시 (study ④)
└─ bench/      측정 전용 엔드포인트 (study ①·③)
docs/          설계 문서(DESIGN)·학습 노트(LEARNING)·이미지
bench/         k6 부하 스크립트
```

---

## 왜 이렇게 만들었나 (설계 노트)

- **RagResult를 sealed interface로**: 답변/컨텍스트 없음/LLM 오류를 타입으로 강제하고, 컨트롤러에서 `switch` 패턴 매칭으로 누락 없이 처리
- **임베딩을 별도 벡터 DB 없이 MySQL JSON에서 출발**: study ③에서 "전수 스캔의 한계와 최적화"를 직접 다루기 위한 의도적 선택. 이후 인메모리 인덱스 → **Elasticsearch kNN**으로 이행하며 정합성·회귀 없음을 측정으로 증명했다(`docs/design-notes.md` §5).
- **로컬 Ollama를 기본값으로**: API 키·비용 없이 클론 후 바로 실행할 수 있다. **다만 품질의 기본값은 아니다** — 골든셋으로 재보니 llama3.2:3b는 환각률 75%였고, 프롬프트로는 그 바닥을 못 깼다. 그래서 **챗 LLM만 교체 가능한 프로바이더 스위치**(`ASKWIKI_LLM_PROVIDER`)를 두고 무료 Gemini로 환각 0%를 실측했다(임베딩은 고정해 원인을 LLM으로 귀속). "키 없이 돈다"와 "품질이 좋다"는 다른 문제이고, 이 레포는 **둘을 섞지 않고 둘 다 말한다**.

자세한 계약(API·스키마·버전)은 [`docs/DESIGN.md`](docs/DESIGN.md).
