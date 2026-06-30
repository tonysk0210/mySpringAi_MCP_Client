package com.example.myspringai_mcp_client.util;

import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * ToolUtil.java 的核心用途是：手動從 List<McpSyncClient> 裡挑出符合「server 名稱 hint」和「tool 名稱 hint」的 MCP tools，
 * 然後把它們包成 ToolCallback[]，讓某一次 ChatClient.prompt() 可以只使用這批工具。 目前它不是全域 filter，而是 per-request / 手動選 tool 的 helper。
 */
public class ToolUtil {

    /**
     * 透過比對 MCP server 名稱與 tool 名稱提示，為單次 request 挑選 MCP tools。
     * null 或空白的提示代表「全部符合」。
     * <p>
     * 不同於 McpServerToolFilter，這個方法不會套用全域封鎖規則；
     * 它會直接從 McpSyncClient 的 tool 清單建立這次 request 專用的 ToolCallbacks。
     */
    public static ToolCallback[] selectToolsFor(List<McpSyncClient> mcpClients, String serverName, String toolName) {

        /**
         * 走訪所有 MCP server clients
         *  -> 每個 client 都列出自己的 tools
         *  -> 只留下 server 名稱和 tool 名稱符合條件的 tools
         *  -> 把這些 tools 包成 Spring AI 的 ToolCallback
         *  -> 回傳這次 request 可用的工具陣列
         */
        // 從所有 McpSyncClient 裡，把符合 serverName 與 toolName 條件的 MCP tools 找出來，包成 ToolCallback[] 回傳。
        return mcpClients.stream()
                .flatMap(client -> client.listTools().tools().stream() // 1. 把每個 client 會回傳一組 tools 攤平成一條 tool stream。
                        // getServerInfo().name() is the same value the global filter reads
                        .filter(tool -> matches(client.getServerInfo().name(), serverName) // 2. MCP server 名稱符合 serverName hint tool 名稱符合 toolName hint
                                && matches(tool.name(), toolName))
                        .map(tool -> (ToolCallback) SyncMcpToolCallback.builder() // 3. MCP 原生 tool 包成 Spring AI 可以使用的 ToolCallback
                                .mcpClient(client)
                                .tool(tool)
                                .build()))
                .toArray(ToolCallback[]::new); // 4. 把結果收集成 ToolCallback[]
    }

    // 這是用來比對字串是否匹配的輔助方法
    private static boolean matches(String actual, String hint) {
        return hint == null || hint.isBlank()
                || actual.toLowerCase().contains(hint.toLowerCase());
    }
}
