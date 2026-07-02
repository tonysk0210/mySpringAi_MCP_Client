package com.example.myspringai_mcp_client.util;

import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpLogging;
import org.springframework.stereotype.Component;

/**
 * 接收 MCP server 傳回來的 log，然後把它轉接到本機 Spring Boot 應用程式的 SLF4J logger 裡。
 */
@Slf4j
@Component
public class HelpDeskLogBridge {

    // clients 要填 MCP client connection name。
    // 本機 stdio server：使用 mcp-servers-*.json 中 mcpServers 底下的 key，例如 helpdesk-ticket-mcp-server-stdio。
    // 遠端 HTTP server：使用 spring.ai.mcp.client.streamable-http.connections.<name> 中的 <name>，例如 myremotemcp。
    @McpLogging(clients = "helpdesk-ticket-mcp-server-stdio")
    public void onServerLog(McpSchema.LoggingLevel level,
                            String source,
                            String message) {
        log.info("收到伺服器日誌 - 等級: {}, 來源: {}, 訊息: {}", level, source, message);
    }
}

/**
 * MCP server tool
 * -> ctx.info("message")
 * -> MCP protocol logging/message notification
 * -> MCP client 收到 notification
 * -> Spring AI 找到 @McpLogging handler
 * -> 呼叫 onServerLog(level, source/logger, message/data)
 * <p>
 * MCP logging notification 本身大概有這些欄位：
 * <p>
 * level   -> INFO / DEBUG / WARNING / ERROR
 * logger  -> 你這裡收到的 source
 * data    -> 你這裡收到的 message
*/