# rag-doc-service - 설계 문서 (SSOT)

> 이 문서는 Codex(스캐폴딩 담당)와 연우님(핵심 학습 모듈 담당)이 **공통으로 따르는 기준**입니다.
> 계약(패키지/엔드포인트/테이블/DTO)을 벗어난 임의 구조 변경 금지. 변경이 필요하면 이 문서를 먼저 고칩니다.

## 1. 무엇을 만드는가

**사내 문서 검색·질의(RAG) API.** 문서를 업로드하면 청킹→임베딩→MySQL 저장하고,
질문을 던지면 유사 청크를 검색해 근거와 함께 LLM 답변을 돌려준다.

하나의 Spring Boot 서비스 안에서 4개의 기술 결정을 각각 study 항목으로 검증한다:

| study | 모듈 | 검증 지표(before→after) | 담당 |
|---|---|---|---|
| ① Virtual Threads 처리량 | `ask` API + `bench` | 동시 200 질의 처리량·p99 | **연우님** |
| ② Spring AI + RAG | `rag` | 로컬 LLM RAG 동작 + 근거 인용 | **연우님** |
| ③ MySQL 벡터 검색 최적화 | `search` | 검색 응답(전수 스캔→후보 축소) | Codex 골격 → 연우님/Claude 튜닝 |
| ④ 캐싱 + 관측성 | `cache` + observability | 응답시간·LLM 호출 절감, Grafana 대시보드 | Codex |

## 2. 기술 스택 (버전 고정)

- Java **21** (LTS), Spring Boot **3.3.x**, Gradle wrapper
- Spring AI **1.0.x** - `spring-ai-ollama-spring-boot-starter`
- MySQL **8.4 (LTS)** - 임베딩은 `JSON` 컬럼에 float 배열로 저장, 유사도는 **애플리케이션에서 계산**
  (community MySQL은 벡터 거리 함수가 없으므로 in-app cosine. study ③의 "최적화"는 후보 축소·정규화·인메모리 인덱스로 다룬다.)
- Ollama - 임베딩 `nomic-embed-text`, 챗 `llama3.2:3b` (로컬·무료, compose가 자동 pull)
- 관측성: Micrometer → Prometheus → Grafana
- 부하: k6

## 3. 패키지 구조 (`com.yeonwoo.ragdoc`)

```
com.yeonwoo.ragdoc
├─ document/     문서 업로드·CRUD, 청킹, Document/Chunk 저장       [Codex]
├─ embedding/    Ollama 임베딩 클라이언트 래퍼, 청크 임베딩         [Codex]
├─ search/       청크 벡터 유사도 검색 (study ③)                    [Codex 골격]
├─ rag/          검색→프롬프트 조립→LLM 호출 오케스트레이션 (study ②) [연우님 TODO]
├─ ask/          POST /api/ask 컨트롤러 - 동시성 핫스팟 (study ①)   [Codex 골격 + 연우님 튜닝]
├─ cache/        질의·임베딩 캐시 (study ④)                          [Codex]
├─ config/       Spring AI·Virtual Threads·관측성 설정              [Codex]
└─ common/       record DTO, sealed 결과 타입, 예외 처리            [Codex]
```

## 4. API 계약

| 메서드 | 경로 | 요청 | 응답 |
|---|---|---|---|
| POST | `/api/documents` | `{ "title": string, "content": string }` | `{ "id", "title", "chunkCount" }` |
| GET | `/api/documents` | - | `[{ "id", "title", "chunkCount", "createdAt" }]` |
| DELETE | `/api/documents/{id}` | - | 204 |
| POST | `/api/ask` | `{ "question": string, "topK"?: int=4 }` | `{ "answer", "sources": [{ "documentId","title","chunkSeq","score" }], "latencyMs", "cached" }` |
| GET | `/actuator/prometheus` | - | Prometheus metrics |

모든 요청/응답 DTO는 **Java record**. LLM/검색 결과는 **sealed interface + 패턴 매칭**으로 성공/실패 표현.

## 5. 데이터 모델 (Flyway `V1__init.sql`)

```sql
CREATE TABLE document (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  title       VARCHAR(255) NOT NULL,
  source      VARCHAR(255),
  created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

CREATE TABLE chunk (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  document_id  BIGINT NOT NULL,
  seq          INT NOT NULL,
  content      TEXT NOT NULL,
  embedding    JSON NOT NULL,           -- float 배열 (nomic-embed-text = 768차원)
  token_count  INT NOT NULL,
  created_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_chunk_document FOREIGN KEY (document_id) REFERENCES document(id) ON DELETE CASCADE,
  INDEX idx_chunk_document (document_id)
);
```

## 6. 모던 Java 21 사용 지점 (의식적으로 노출)

- **Record**: 모든 DTO (`AskRequest`, `AskResponse`, `Source`, `ChunkMatch` …)
- **Sealed interface + switch 패턴 매칭**: `RagResult` = `Answered | NoContext | LlmError`
- **Virtual Threads**: study ①에서 `ask` 처리 경로에 적용 (연우님)
- 텍스트 블록: 프롬프트 템플릿

## 7. 실행 (docker compose 완결 - 호스트에 JDK 불필요)

앱 컴파일은 Dockerfile build 스테이지의 `gradle:8.10-jdk21`가 담당한다. **호스트엔 Docker만 있으면 되고 시스템 Java 8을 그대로 둬도 된다** (Gradle wrapper 불필요).

```bash
docker compose up --build -d              # app(Java 21 컨테이너) + mysql + ollama + prometheus + grafana
# 최초 1회만 Ollama 모델 pull (자동 아님)
docker compose exec ollama ollama pull nomic-embed-text
docker compose exec ollama ollama pull llama3.2:3b
```
- app: http://localhost:8080, Grafana: http://localhost:3000, Prometheus: http://localhost:9090
- 학습 코드 수정 후 앱만 재빌드: `docker compose up --build -d app`
- IntelliJ 개발(선택): 시스템 Java 8은 두고, **프로젝트 전용 JDK 21만** 내려받아 지정
  (`Project Structure → SDKs → Download JDK → Temurin 21`). 빌드·실행은 여전히 Docker.

## 8. 경계 - 연우님이 직접 하는 부분 (Codex는 손대지 않음)

Codex는 아래 두 곳을 **컴파일되는 빈 스텁 + `// TODO(연우): ...` 주석 + 실패하는/무시된 테스트**로만 남긴다.

1. `rag/RagService#answer(question, topK)` - 검색 호출 → 프롬프트 조립 → LLM 호출 → 결과 매핑 (study ②)
2. `ask` 경로의 Virtual Threads 적용 + `bench/` 부하 비교 도구 (study ①)

각 스텁 위치와 학습 노트는 `docs/LEARNING-rag.md`, `docs/LEARNING-virtual-threads.md` 참고.
