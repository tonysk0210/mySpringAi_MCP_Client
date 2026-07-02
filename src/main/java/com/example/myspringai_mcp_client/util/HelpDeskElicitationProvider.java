package com.example.myspringai_mcp_client.util;

import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpElicitation;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * MCP client 端的 elicitation handler。當指定的 MCP server 要求 client 補資料時，這個 component 會接收請求，模擬使用者填入資料，然後回傳 ACCEPT。
 */
@Slf4j
@Component
public class HelpDeskElicitationProvider {

    /**
     * handleElicitationRequest(McpSchema.ElicitRequest request) 當 MCP server 發出 elicitation request 時，Spring AI MCP 會呼叫這個 method，並把 server 的 request 包成 ElicitRequest 傳進來。
     */
    @McpElicitation(clients = "helpdesk-ticket-mcp-server-stdio")
    public McpSchema.ElicitResult handleElicitationRequest(McpSchema.ElicitRequest request) {

        log.info("收到伺服器傳來的 MCP elicitation 請求：{}", request.message());

        // 模擬使用者填寫伺服器要求的表單。這裡的 key 必須符合
        // 伺服器請求 schema 的欄位名稱（TicketContactInfo: priority, contactPhone）。
        // 1. userResponse 這裡模擬使用者填表：
        Map<String, Object> userResponse = Map.of(
                "priority", "HIGH",
                "contactPhone", "+1-202-555-0185");

        log.info("以 ACCEPT 回應 elicitation，資料為：{}", userResponse);

        // 2. 回傳 ElicitResult.Action.ACCEPT 代表 client 接受這次 elicitation，並把 userResponse 當成內容送回 server
        return McpSchema.ElicitResult.builder(McpSchema.ElicitResult.Action.ACCEPT)
                .content(userResponse)
                .build();
    }
}
