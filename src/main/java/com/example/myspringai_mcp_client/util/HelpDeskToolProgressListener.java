package com.example.myspringai_mcp_client.util;

import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpProgress;
import org.springframework.stereotype.Component;

/**
 * 當你的 Spring AI MCP client 呼叫 helpdesk-ticket-mcp-server-stdio 這個 MCP server 的 tool，
 * 而且該 server 在執行過程中送出 progress notification 時，Spring AI 會呼叫這個 onProgress(...) method，然後把進度印到 log。
 */
@Slf4j
@Component
public class HelpDeskToolProgressListener {

    /**
     * 只要 MCP server 送出 progress notification，這個 method 就會被呼叫。
     * 所以真正決定它什麼時候發生的是 MCP server 端什麼時候送 progress
     */
    @McpProgress(clients = "helpdesk-ticket-mcp-server-stdio")
    public void onProgress(McpSchema.ProgressNotification notification) {
        log.info("進度更新 - 已完成 {}%，請求 ID：{}，訊息：{}",
                notification.progress(),
                notification.progressToken(),
                notification.message());
    }
}
/**
 * toolContext progressToken
 * -> 傳給 MCP server
 * -> server 執行 tool 時用同一個 token 回報 progress
 * -> HelpDeskToolProgressListener 收到 progress notification
 * -> 用 notification.progressToken() 印出是哪一次 tool call 的進度
 */