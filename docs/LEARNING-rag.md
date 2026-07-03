# 학습 과제 ② - Spring AI + RAG 파이프라인

> study 제목: "Spring AI로 로컬 LLM 기반 RAG 구현"
> 목표: "Java로 AI가 실제로 된다"를 자기 손으로 증명한다. 검색된 근거를 프롬프트에 넣어
> **환각을 줄이고 출처를 인용**하는 RAG의 핵심 흐름을 직접 조립한다.

## 먼저 이해할 개념

- **RAG (Retrieval-Augmented Generation)**: LLM에 그냥 물으면 학습 안 된 사내 문서는 모른다(→환각).
  RAG는 ① 질문을 임베딩 → ② 유사한 문서 청크를 검색 → ③ 그 청크를 프롬프트에 **컨텍스트로 주입** →
  ④ "이 컨텍스트만 근거로 답하라"고 지시. 그래서 최신·사내 지식으로 답하고 출처를 댈 수 있다.
- **임베딩**: 텍스트를 의미 공간의 벡터로 바꾼 것. 의미가 가까우면 벡터도 가깝다(코사인 유사도).
- **왜 청킹**: 문서 전체를 넣으면 컨텍스트 초과 + 노이즈. 문단 단위로 쪼개 관련 조각만 넣는다.
- Spring AI가 `EmbeddingModel`, `ChatModel` 추상화를 준다. 우리는 Ollama 구현을 주입받는다.

## 무엇을 구현하나

Codex가 주변을 다 만들어 둔다: 임베딩 클라이언트(`embedding/`), 검색(`search/SearchService`),
DTO/sealed 타입(`common/`), 컨트롤러(`ask/`). **당신은 오케스트레이션 한 곳만 채운다.**

파일: `rag/RagService.java`
```java
// TODO(연우): RAG 핵심 흐름
public RagResult answer(String question, int topK) {
    // 1. 질문을 임베딩          -> embeddingClient.embed(question)
    // 2. 유사 청크 topK 검색     -> searchService.findSimilar(questionVector, topK)
    //    (검색 결과가 비면 RagResult.NoContext 반환)
    // 3. 프롬프트 조립          -> 텍스트 블록 템플릿에 컨텍스트(청크들) + 질문 삽입
    //    "아래 컨텍스트만 근거로 답하고, 모르면 모른다고 답하라. [출처] 표기." 지시 포함
    // 4. chatModel.call(prompt) 로 답변 생성
    // 5. 답변 + 사용한 청크들을 Source로 매핑해 RagResult.Answered 반환
    //    LLM 예외는 RagResult.LlmError로 감싼다 (sealed 타입 + 호출부 패턴 매칭)
}
```

### 당신의 과제
- [ ] 위 5단계 구현.
- [ ] 프롬프트 엔지니어링: "컨텍스트에 없으면 지어내지 말 것"을 명시. 텍스트 블록(`"""`) 사용.
- [ ] `RagResult`를 sealed interface로 두고 컨트롤러에서 `switch` 패턴 매칭으로 처리(모던 Java 노출).
- [ ] 근거 인용: 응답 `sources`에 실제 사용한 청크의 documentId/title/score를 담는다.

## 검증

```bash
# 문서 몇 개 업로드
curl -X POST localhost:8080/api/documents -H "Content-Type: application/json" \
  -d '{"title":"휴가 규정","content":"연차는 입사 1년 후 15일이 부여된다. ..."}'
# 질문
curl -X POST localhost:8080/api/ask -H "Content-Type: application/json" \
  -d '{"question":"연차는 며칠 나오나요?"}'
# -> answer에 "15일", sources에 방금 그 문서가 잡히면 성공
```

증거: 문서에 없는 걸 물으면 "모른다"고 답해야 한다(환각 억제 검증). 이 대비 스크린샷을 스터디에 넣는다.

## 학습 확인 질문 (면접 대비)
1. RAG는 파인튜닝과 뭐가 다르고 언제 각각을 쓰나?
2. 임베딩 유사도로 검색하면 키워드 검색 대비 뭐가 좋은가?
3. 청크 크기가 너무 크거나 작으면 각각 무슨 문제가 생기나?
4. 컨텍스트에 엉뚱한 청크가 섞이면(낮은 정밀도) 답변에 어떤 영향? 어떻게 완화하나?
