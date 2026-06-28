package com.example.myspringai_mcp_client.controller;

import com.example.myspringai_mcp_client.advisor.PrettyLoggerAdvisor;
import com.example.myspringai_mcp_client.payload.ChatPayload;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class McpClientController {

    private final ChatClient chatClient;

    public McpClientController(ChatClient.Builder chatClientBuilder, ToolCallbackProvider toolCallbackProvider) {
        this.chatClient = chatClientBuilder
                .defaultSystem("""
                        回答時請使用清楚、易理解且專業的繁體中文。
                        當使用者詢問 GitHub 相關操作時，必須使用 GitHub MCP 工具（如 get_file_contents、list_files、search_repositories 等），絕對不可使用 filesystem 工具。
                        當使用者詢問本機檔案操作時，才使用 filesystem 工具。
                        """)
                .defaultAdvisors(new SimpleLoggerAdvisor(), new PrettyLoggerAdvisor())
                .defaultTools(toolCallbackProvider) // ToolCallbackProvider: Spring AI 收集到的「可供 LLM 呼叫的工具清單來源」。
                .build();
    }

    @PostMapping("/chat")
    public String chat(@RequestBody ChatPayload chatPayload) {
        return chatClient.prompt()
                .user(chatPayload.message())
                .call().content();
    }

}