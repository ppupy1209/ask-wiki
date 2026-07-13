package com.yeonwoo.askwiki.mcp;

import com.yeonwoo.askwiki.ask.AskService;
import com.yeonwoo.askwiki.embedding.EmbeddingClient;
import com.yeonwoo.askwiki.search.VectorIndex;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WikiMcpToolRegistrationTest {

    @Test
    void registersExactlySearchAndAskWikiTools() {
        ToolCallbackProvider provider = MethodToolCallbackProvider.builder()
                .toolObjects(new WikiMcpTools(
                        mock(EmbeddingClient.class),
                        mock(VectorIndex.class),
                        mock(AskService.class)))
                .build();

        List<String> toolNames = Arrays.stream(provider.getToolCallbacks())
                .map(callback -> callback.getToolDefinition().name())
                .toList();

        assertThat(toolNames).containsExactlyInAnyOrder("search_wiki", "ask_wiki");
    }
}
