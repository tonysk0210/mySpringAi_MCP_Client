package com.example.myspringai_mcp_client.controller;

import com.example.myspringai_mcp_client.advisor.PrettyLoggerAdvisor;
import com.example.myspringai_mcp_client.advisor.TokenUsageAuditAdvisor;
import com.example.myspringai_mcp_client.payload.ChatPayload;
import com.example.myspringai_mcp_client.util.ToolUtil;
import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class McpClientController {

    private final ChatClient chatClient;

    // List<McpSyncClient> mcpClients 代表：Spring AI 幫你建立好的「所有 MCP server 連線 client」。
    /* 所以概念上像這樣：
    mcpClients = [
      McpSyncClient for filesystem MCP server,
      McpSyncClient for github MCP server,
      McpSyncClient for helpdesk-ticket-mcp-server-stdio
    ]
     */
    private final List<McpSyncClient> mcpClients;


    public McpClientController(ChatClient.Builder chatClientBuilder, ToolCallbackProvider toolCallbackProvider, List<McpSyncClient> mcpClients) {
        this.chatClient = chatClientBuilder
                .defaultSystem("""
                        回答時請使用清楚、易理解且專業的繁體中文。
                        當使用者詢問 GitHub 相關操作時，必須使用 GitHub MCP 工具（如 get_file_contents、list_files、search_repositories 等），絕對不可使用 filesystem 工具。
                        當使用者詢問本機檔案操作時，才使用 filesystem 工具。
                        """)
                .defaultAdvisors(new PrettyLoggerAdvisor(), new TokenUsageAuditAdvisor()) // new SimpleLoggerAdvisor() 停用
                .defaultTools(toolCallbackProvider) // ToolCallbackProvider: Spring AI 收集到的「可供 LLM 呼叫的工具清單來源」。
                .build();
        this.mcpClients = mcpClients;
    }


    @PostMapping("/chat")
    public String chat(@RequestBody ChatPayload chatPayload, @RequestHeader(value = "username", required = false) String username) {

        // 1. 選擇合適的 ToolCallback
        ToolCallback[] toolCallbacks = ToolUtil.selectToolsFor(mcpClients, "secure-filesystem-server", "list_directory");

        return chatClient.prompt()
                .user(chatPayload.message() + ". 我的 username 是 " + username)
                // .tools(toolCallbacks)
                // 為本次 MCP tool call 附加 progressToken；若 MCP server 支援進度通知，可用它標識這次工具執行。
                // 如果這次 AI 呼叫 MCP tool，就把一個唯一 progressToken 一起帶給 MCP server，讓支援進度通知的 server 可以用它追蹤並回報這次工具執行進度。
                .toolContext(Map.of("progressToken", UUID.randomUUID().toString()))
                .call().content();
    }

    /**
     * 專門用於展示 MCP sampling 的端點。這裡仍然是由聊天 LLM 主導：它看到
     * {@code summarizeTickets} 這個工具後，自行決定要呼叫它。該工具實際上是在
     * server 端執行，執行過程中會回呼（callback）到這個 client 端的
     * {@code @McpSampling} 處理器，以產生自然語言摘要。這個 controller
     * 本身從未直接呼叫該工具。
     */
    @GetMapping("/summarize-tickets")
    public String summarizeTickets(@RequestHeader("username") String username) {
        ToolCallback[] toolCallbacks = ToolUtil.selectToolsFor(mcpClients, "helpdesk-ticket-mcp-server-stdio", null);
        return chatClient.prompt()
                .system("""
                        你負責調度 'summarizeTickets' 這個工具。該工具針對這次請求，
                        已經產生了一段完整、可直接呈現給客戶的摘要。請將該工具的
                        輸出「原封不動」回傳給使用者：不得重寫、重新排版、精簡、
                        擴寫、改述，也不得加上任何你自己的評論。你的回覆必須是
                        工具回應的逐字內容，不得有其他任何文字。
                        """)
                .user("請幫我摘要所有的服務工單。我的使用者名稱是 " + username)
                .tools(toolCallbacks)
                .toolContext(Map.of("progressToken", UUID.randomUUID().toString()))
                .call().content();
    }
}

/**
 * defaultTools + tools        -> request tools 會加到 default tools，不是取代；同名 tool 會造成重複名稱錯誤
 * defaultSystem + system      -> request system 取代 default system
 * defaultAdvisors + advisors  -> request advisors 加到 default advisors 後面/同一條 chain
 * <p>
 * 1. ToolCallbackProvider 內部會用 prefix generator 處理它自己展開出的 MCP tools； same tool name 會加上 prefix
 * 2. 但你手動建立的 ToolCallback[] 沒有走同一個命名去重流程。 same tool name 會造成重複名稱錯誤
 * <p>
 * 要全域 MCP tools -> 用 defaultTools(toolCallbackProvider)
 * 要 request-level 精準選 tools -> 不設 defaultTools，只用 .tools(toolCallbacks)
 */