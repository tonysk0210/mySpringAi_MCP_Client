package com.example.myspringai_mcp_client.controller;

import com.example.myspringai_mcp_client.advisor.PrettyLoggerAdvisor;
import com.example.myspringai_mcp_client.advisor.TokenUsageAuditAdvisor;
import com.example.myspringai_mcp_client.payload.ChatPayload;
import com.example.myspringai_mcp_client.util.ElicitationSessionStore;
import com.example.myspringai_mcp_client.util.ElicitationSseService;
import com.example.myspringai_mcp_client.util.ToolUtil;
import io.modelcontextprotocol.client.McpSyncClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class McpClientController {

    private final ChatClient chatClient;

    // 僅用於解析 elicitation 回應的輕量 client：不帶 MCP tools、不帶 advisor，
    // 只做「把使用者自然語言輸入轉成 JSON」這件事，避免觸發不必要的 tool call。
    private ChatClient parserClient;

    private final List<McpSyncClient> mcpClients; // 已建立連線的 MCP server 清單，目前在這個 controller 裡是用來做精準的 tool 篩選
    private final ElicitationSessionStore elicitationSessionStore;
    private final ElicitationSseService elicitationSseService;

    @Autowired
    public McpClientController(ChatClient.Builder chatClientBuilder,
                               ToolCallbackProvider toolCallbackProvider,
                               List<McpSyncClient> mcpClients,
                               ElicitationSessionStore elicitationSessionStore,
                               ElicitationSseService elicitationSseService) {

        // parserClient 必須在 chatClientBuilder 加入 defaultTools 之前建立，
        // 才能確保它是不帶任何 MCP tools 的乾淨 client。
        this.parserClient = chatClientBuilder.build();

        this.chatClient = chatClientBuilder
                .defaultSystem("""
                        回答時請使用清楚、易理解且專業的繁體中文。
                        當使用者詢問 GitHub 相關操作時，必須使用 GitHub MCP 工具（如 get_file_contents、list_files、search_repositories 等），絕對不可使用 filesystem 工具。
                        當使用者詢問本機檔案操作時，才使用 filesystem 工具。
                        """)
                .defaultAdvisors(new TokenUsageAuditAdvisor(), new PrettyLoggerAdvisor())
                .defaultTools(toolCallbackProvider)
                .build();

        this.mcpClients = mcpClients;
        this.elicitationSessionStore = elicitationSessionStore;
        this.elicitationSseService = elicitationSseService;
    }

    /**
     * 主要聊天端點。
     * <p>
     * 流程：
     * 1. 若當前有 pending elicitation session（MCP server 正等待補充資料），
     * 則把這則訊息視為 elicitation 回應，用 LLM 解析後完成 session。
     * 2. 否則走正常 LLM chat 流程。
     * <p>
     * 注意：步驟 1 的情境下，此端點會立即返回確認訊息；
     * LLM 最終回覆會透過原先仍在阻塞等待的 chat 呼叫返回給前端。
     */
    @PostMapping("/chat")
    public String chat(@RequestBody ChatPayload chatPayload,
                       @RequestHeader(value = "username", required = false) String username) {

        // 攔截 elicitation 補充輸入：
        // 若 MCP server 正在等待補充資料（hasPending() = true），
        // 代表前一次 POST /api/chat 觸發了 tool call，server 在執行過程中發出 elicitation，
        // SSE 已把提示與 schema 推給前端，handleElicitationRequest() 的 thread 目前阻塞等待。
        // 此次 POST 是使用者看到提示後在聊天框輸入的補充回應，
        // 應交由 handleElicitationChatResponse() 用 LLM 解析成 Map，
        // 再呼叫 sessionStore.complete() 喚醒阻塞中的 thread，讓 server 繼續完成 tool 執行。
        if (elicitationSessionStore.hasPending()) {
            return handleElicitationChatResponse(chatPayload.message());
        }

        // 選擇合適的 MCP tools：只選擇屬於 "secure-filesystem-server" 的 tools，且工具名稱為 "list_directory"
        ToolCallback[] toolCallbacks = ToolUtil.selectToolsFor(mcpClients, "secure-filesystem-server", "list_directory");

        return chatClient.prompt()
                .user(chatPayload.message() + ". 我的 username 是 " + username)
                .toolContext(Map.of("progressToken", UUID.randomUUID().toString()))
                .call().content();
    }
    /**
     *   進入 hasPending() 這個 block 代表：
     *
     *   時序軸
     *   ──────────────────────────────────────────────────────────
     *   [第一次 POST /api/chat] → LLM 呼叫 createTicket tool
     *       │
     *       ▼  MCP server 發出 elicitation → handleElicitationRequest() 被呼叫
     *       │   sessionStore.register()  → session 建立，hasPending() = true
     *       │   sseService.push()        → SSE 把 prompt + schema 推給前端
     *       │   future.get()             → 此 thread 阻塞，等待...
     *       │
     *       │  （使用者看到聊天框出現提示，輸入回應後按 Enter）
     *       │
     *       ▼
     *   [第二次 POST /api/chat] → hasPending() = true → 進入這個 block
     *       │
     *       │  handleElicitationChatResponse(userMessage)
     *       │   parserClient + LLM → 把自然語言解析成 Map
     *       │   sessionStore.complete() → future.get() 解除阻塞
     *       │
     *       ▼  立即回傳 "✅ 資料已收到，正在繼續處理..."  給這次 POST
     *       │
     *       ▼  第一次被阻塞的 POST 解除，MCP server 繼續 createTicket
     *          LLM 最終回覆透過第一次的回應返回給前端
     */

    /**
     * 把使用者在聊天框的輸入解析成 elicitation 所需的 JSON 資料，
     * 然後呼叫 ElicitationSessionStore.complete() 解除 handleElicitationRequest() 的阻塞。
     * <p>
     * 使用 parserClient（不帶 MCP tools）呼叫 LLM，
     * 讓 LLM 根據 server 原始提示訊息理解需要哪些欄位，
     * 再從使用者的自然語言輸入中萃取對應的值，回傳純 JSON。
     */
    private String handleElicitationChatResponse(String userMessage) {

        // 步驟 1：取出目前等待中的 elicitation session。
        // firstPending() 回傳 sessionId 和 serverMessage（MCP server 的原始提示文字）。
        // 若 session 在取得前已逾時或被取消，回傳 null，直接告知使用者無待處理請求。
        ElicitationSessionStore.PendingSession pending = elicitationSessionStore.firstPending()
                .orElse(null);
        if (pending == null) return "沒有待處理的補充資料請求。";

        log.info("偵測到 pending elicitation，嘗試解析使用者輸入 sessionId={}", pending.sessionId());

        try {
            // 步驟 2：用 parserClient（不帶任何 MCP tools 的純淨 LLM client）解析使用者輸入。
            // prompt 同時提供兩個上下文：
            //   - pending.serverMessage()：MCP server 的原始提示，說明需要哪些欄位與格式 - "在開立服務工單之前，請選擇優先等級（LOW、MEDIUM、HIGH 或 URGENT），並提供聯絡電話，以便我們的團隊與您聯繫。"
            //   - userMessage：使用者在聊天框輸入的自然語言回應（例如：「HIGH，0912-345-678」）
            // LLM 的任務是對照 serverMessage 理解欄位定義，再從 userMessage 中萃取對應值，
            // 只回傳純 JSON，不包含任何說明文字。
            //
            // .entity(Map.class) 讓 Spring AI 直接把 LLM 回傳的 JSON 字串反序列化成 Map，
            // 不需要手動注入 ObjectMapper，也避免 Jackson 2.x / 3.x 版本差異問題。
            @SuppressWarnings("unchecked") // 這個 annotation 是開發者對編譯器說：「我比你更清楚這裡的型別，不用提醒我。」- .entity(Map.class);
            Map<String, Object> data = parserClient.prompt()
                    .user("""
                            Server 向使用者要求補充的資料說明如下：
                            「%s」
                            
                            使用者的回應為：
                            「%s」
                            
                            請根據以上資訊，從使用者回應中萃取所需欄位，只回傳純 JSON，不要有任何其他文字。
                            範例格式：{"priority":"HIGH","contactPhone":"+886-2-1234-5678"}
                            """.formatted(pending.serverMessage(), userMessage))
                    .call().entity(Map.class);

            // 步驟 3：將解析好的 Map 傳入 complete()，完成 CompletableFuture。
            // 這會喚醒 HelpDeskElicitationProvider.handleElicitationRequest() 中
            // 阻塞在 future.get() 的 thread，讓它取得 data 並回傳 ElicitResult.ACCEPT 給 MCP server，
            // server 即可繼續完成原本暫停的 tool 執行。
            elicitationSessionStore.complete(pending.sessionId(), data);
            log.info("Elicitation session 已完成 sessionId={} data={}", pending.sessionId(), data);

            // 步驟 4：立即回傳確認訊息給這次 POST /api/chat 的呼叫方（前端聊天框）。
            // LLM 的最終回覆（tool 執行完成後）會透過第一次仍在阻塞的 POST /api/chat 回傳，
            // 因此這裡只需要告知使用者「資料已收到，正在處理中」，不需要等待 tool 完成。
            return "✅ 資料已收到，正在繼續處理，請稍候...";

        } catch (Exception e) {
            // LLM 解析失敗（例如使用者輸入格式完全無法對應欄位）時，
            // session 保持 pending 狀態，使用者可重新輸入後再次送出。
            log.warn("無法解析使用者的 elicitation 回應", e);
            return "❌ 無法解析您的輸入，請重新輸入（例如：HIGH，+886-2-1234-5678）。";
        }
    }

    /**
     * 這個端點讓瀏覽器訂閱一條「等通知用的長連線」，平時沉默，一旦 MCP server 發出 elicitation，server 就透過這條連線即時把提示推到聊天框，不需要前端輪詢（polling）。
     * <p>
     * 前端範例：
     * const es = new EventSource('/api/elicitation/stream');
     * es.addEventListener('elicitation', e => {
     * const { prompt } = JSON.parse(e.data);
     * chatBox.appendBotMessage('⚠️ ' + prompt);
     * });
     */
    @GetMapping(value = "/elicitation/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter elicitationStream() {
        return elicitationSseService.subscribe();
    }

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
 * 1. ToolCallbackProvider 內部會用 prefix generator 處理它自己展開出的 MCP tools；same tool name 會加上 prefix
 * 2. 但你手動建立的 ToolCallback[] 沒有走同一個命名去重流程。same tool name 會造成重複名稱錯誤
 * <p>
 * 要全域 MCP tools -> 用 defaultTools(toolCallbackProvider)
 * 要 request-level 精準選 tools -> 不設 defaultTools，只用 .tools(toolCallbacks)
 */
