package com.example.myspringai_mcp_client.controller;

import com.example.myspringai_mcp_client.advisor.PrettyLoggerAdvisor;
import com.example.myspringai_mcp_client.advisor.TokenUsageAuditAdvisor;
import com.example.myspringai_mcp_client.payload.ChatPayload;
import com.example.myspringai_mcp_client.util.ElicitationSessionStore;
import com.example.myspringai_mcp_client.util.ElicitationSseService;
import com.example.myspringai_mcp_client.util.ToolUtil;
import io.modelcontextprotocol.client.McpSyncClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Helpdesk MCP 端點，僅提供 helpdesk-ticket-mcp-server-stdio 的工具。
 * filesystem / GitHub 相關功能由 {@link McpClientController} 負責。
 * <p>
 * 包含 elicitation 機制：當 MCP server 在 tool 執行中途需要補充資訊時，
 * 透過 SSE 把問題推給前端，使用者回覆後再由此 controller 解析並喚醒阻塞的 thread。
 */
@Slf4j
@RestController
@RequestMapping("/api/helpdesk")
public class McpHelpdeskController {

    private final ChatClient chatClient;

    // 僅用於 elicitation 回應解析：不帶任何 MCP tools、advisor、記憶，
    // 只負責把使用者自然語言輸入轉成 JSON，避免觸發不必要的 tool call。
    private ChatClient parserClient;

    private final PrettyLoggerAdvisor prettyLoggerAdvisor = new PrettyLoggerAdvisor();

    // 此 controller 專屬的記憶體，與 McpClientController 的 chatMemory 完全隔離。
    // 即使前端傳入相同的 conversationId，兩個 controller 查的是不同的 store，不會互相汙染。
    private final ChatMemory chatMemory = MessageWindowChatMemory.builder().build();

    // helpdesk tools，啟動時查詢一次並快取，與其他 controller 的 tools 完全獨立。
    private final ToolCallback[] tools;

    private final ElicitationSessionStore elicitationSessionStore;
    private final ElicitationSseService elicitationSseService;

    @Autowired
    public McpHelpdeskController(ChatClient.Builder chatClientBuilder,
                                 ChatClient.Builder parserClientBuilder,
                                 List<McpSyncClient> mcpClients,
                                 ElicitationSessionStore elicitationSessionStore,
                                 ElicitationSseService elicitationSseService) {

        // 使用 server 在 MCP 協定層回報的實際名稱（getServerInfo().name() = "mySpringAi_MCP_Server_stdio"）做比對，
        // 注意：這個名稱來自 server 本身的 spring.application.name，不是 application.properties 的 connection key。
        this.tools = ToolUtil.selectToolsFor(mcpClients, "mySpringAi_MCP_Server_stdio", null);

        // ChatClient.Builder 是 prototype scope，每個 constructor parameter 各自注入獨立實例，
        // parserClientBuilder 與 chatClientBuilder 完全隔離，不共用任何內部狀態。
        this.parserClient = parserClientBuilder.build();

        this.chatClient = chatClientBuilder
                .defaultSystem("""
                        回答時請使用清楚、易理解且專業的繁體中文。
                        你是一位 IT Helpdesk 智慧助理，負責協助使用者排除技術問題並管理服務工單。

                        ## 可用工具說明

                        - `troubleshootIssue`：AI 排障分析。輸入使用者名稱與問題描述，工具會參考歷史解決案例，回傳可能原因、排查步驟，以及是否建議開立工單。
                        - `createTicket`：建立服務工單。工具執行時會自動透過 elicitation 向使用者收集優先等級（LOW/MEDIUM/HIGH/URGENT）與聯絡電話；使用者若拒絕提供，則以預設值（MEDIUM、無電話）建立。
                        - `getTicketStatus`：查詢指定使用者的所有工單清單與狀態。

                        ## 標準處理流程

                        1. 使用者描述技術問題 → **一律先呼叫 `troubleshootIssue`**，把 AI 排障建議回覆給使用者
                        2. 詢問使用者是否已依建議嘗試，或問題是否解決
                        3. 決定開立工單前，**必須先呼叫 `getTicketStatus`** 查詢該使用者的現有工單：
                           - 若已有狀態為 OPEN 或 IN_PROGRESS 且問題描述相似的工單 → **不得開立新工單**，
                             直接告知使用者並列出相關工單的編號、狀態與建立時間
                           - 若無相似的進行中工單 → 繼續執行步驟 4
                        4. 呼叫 `createTicket` 的條件（滿足其一且步驟 3 確認無重複工單）：
                           - 使用者確認排障步驟無效
                           - 使用者主動要求開立工單
                           - `troubleshootIssue` 的建議結論為「建議開立服務工單」

                        ## 禁止行為

                        - 使用者描述問題時跳過 `troubleshootIssue`，直接呼叫 `createTicket`
                        - 使用者尚未確認排障結果前，主動建議或引導開單
                        - 自行推理排障內容：排障分析應由 `troubleshootIssue` 工具執行，不得自己回答
                        - **收到技術問題時，禁止先輸出任何文字說明（如「我將使用工具…」），必須直接呼叫 `troubleshootIssue`，工具執行完畢後再根據結果回覆**
                        - 未先呼叫 `getTicketStatus` 確認無重複工單，直接呼叫 `createTicket`
                        """)
                .defaultAdvisors(
                        new TokenUsageAuditAdvisor(),
                        this.prettyLoggerAdvisor,
                        // MessageChatMemoryAdvisor 負責把對話歷史注入每次 LLM 請求，
                        // 並在收到回覆後把新的訊息存回 chatMemory。
                        // conversationId 為必要欄位，每次 request 透過 advisors param 傳入。
                        MessageChatMemoryAdvisor.builder(this.chatMemory).build())
                .build();

        this.elicitationSessionStore = elicitationSessionStore;
        this.elicitationSseService = elicitationSseService;
    }

    /**
     * Helpdesk 聊天端點，具備對話記憶，支援多輪的排障 → 確認 → 開單流程。
     * <p>
     * 流程：
     * 1. 若 MCP server 正等待使用者補充資料（elicitation pending），
     *    把本次訊息視為 elicitation 回應，解析後喚醒阻塞中的 thread。
     * 2. 否則走正常 LLM chat 流程（帶記憶）。
     * <p>
     * 時序（elicitation 情境）：
     * <pre>
     * [第一次 POST /api/helpdesk/chat] → LLM 呼叫 createTicket tool
     *     │
     *     ▼  server 發出 elicitation → sessionStore.register()，hasPending()=true
     *        sseService.push() → SSE 把提示推給前端，future.get() 阻塞
     *     │
     *     │（使用者在聊天框輸入補充資料後送出）
     *     ▼
     * [第二次 POST /api/helpdesk/chat] → hasPending()=true → handleElicitationChatResponse()
     *        parserClient 解析自然語言 → sessionStore.complete() 解除阻塞
     *     ▼  立即回傳確認訊息，第一次的 POST 繼續完成 tool 並回傳最終結果
     * </pre>
     */
    @PostMapping("/chat")
    public String chat(@RequestBody ChatPayload chatPayload,
                       @RequestHeader(value = "username", required = false) String username) {
        if (elicitationSessionStore.hasPending()) {
            return handleElicitationChatResponse(chatPayload.message());
        }

        prettyLoggerAdvisor.reset();

        return chatClient.prompt()
                .user(chatPayload.message() + ". 我的 username 是 " + username)
                .tools(tools)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, username))
                .toolContext(Map.of("progressToken", UUID.randomUUID().toString()))
                .call().content();
    }

    /**
     * SSE 長連線訂閱端點。瀏覽器訂閱後平時沉默，
     * 一旦 MCP server 發出 elicitation，立即透過此連線把提示推到聊天框。
     * <p>
     * 前端範例：
     * <pre>
     * const es = new EventSource('/api/helpdesk/elicitation/stream');
     * es.addEventListener('elicitation', e => {
     *   const { prompt } = JSON.parse(e.data);
     *   chatBox.appendBotMessage('⚠️ ' + prompt);
     * });
     * </pre>
     */
    @GetMapping(value = "/elicitation/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter elicitationStream() {
        return elicitationSseService.subscribe();
    }

    // //////////////////////////////////////////
    // Private helpers
    // //////////////////////////////////////////

    /**
     * 把使用者在聊天框輸入的自然語言解析成 elicitation 所需的 JSON，
     * 再呼叫 ElicitationSessionStore.complete() 解除阻塞中的 thread。
     * <p>
     * 使用 parserClient（不帶 MCP tools、無記憶）讓 LLM 對照 server 原始提示萃取欄位值，
     * .entity(Map.class) 讓 Spring AI 直接把 JSON 反序列化成 Map，省去手動解析。
     */
    private String handleElicitationChatResponse(String userMessage) {

        // 步驟 1：取出目前等待中的 session。
        // 若 session 已逾時或被取消，直接告知使用者。
        ElicitationSessionStore.PendingSession pending = elicitationSessionStore.firstPending()
                .orElse(null);
        if (pending == null) return "沒有待處理的補充資料請求。";

        log.info("偵測到 pending elicitation，嘗試解析使用者輸入 sessionId={}", pending.sessionId());

        try {
            // 步驟 2：用 parserClient 解析使用者輸入。
            // prompt 同時提供兩個上下文：
            //   - pending.serverMessage()：server 的原始提示，說明需要哪些欄位（例如：「請選擇優先等級與聯絡電話」）
            //   - userMessage：使用者的自然語言回應（例如：「HIGH，0912-345-678」）
            @SuppressWarnings("unchecked") // .entity(Map.class) 回傳 raw Map，此轉型由 LLM 回傳格式保證安全
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

            // 步驟 3：complete() 會喚醒 HelpDeskElicitationProvider 中阻塞在 future.get() 的 thread，
            // 讓 server 拿到資料後繼續完成 tool 執行。
            elicitationSessionStore.complete(pending.sessionId(), data);
            log.info("Elicitation session 已完成 sessionId={} data={}", pending.sessionId(), data);

            // 步驟 4：立即回傳確認訊息給本次 POST（前端聊天框）。
            // tool 的最終結果會透過第一次仍在阻塞的 POST 回傳，不需在此等待。
            return "✅ 資料已收到，正在繼續處理，請稍候...";

        } catch (Exception e) {
            // 解析失敗時，session 維持 pending，使用者可重新輸入。
            log.warn("無法解析使用者的 elicitation 回應", e);
            return "❌ 無法解析您的輸入，請重新輸入（例如：HIGH，+886-2-1234-5678）。";
        }
    }

}
