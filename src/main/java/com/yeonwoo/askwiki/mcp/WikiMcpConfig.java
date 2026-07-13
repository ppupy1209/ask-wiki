package com.yeonwoo.askwiki.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WikiMcpConfig {

    @Bean
    public ToolCallbackProvider wikiTools(WikiMcpTools wikiMcpTools) {
        return MethodToolCallbackProvider.builder().toolObjects(wikiMcpTools).build();
    }
}
