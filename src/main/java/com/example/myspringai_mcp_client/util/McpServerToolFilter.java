package com.example.myspringai_mcp_client.util;

import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.McpConnectionInfo;
import org.springframework.ai.mcp.McpToolFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 當 MCP server 提供 tools 給 Spring AI client 時，這個 class 會決定哪些 tool 可以被使用、哪些 tool 要被擋掉。
 * 它控制的是你的 Spring Boot application 要不要把某個 MCP tool 暴露給 LLM 使用
 */
@Slf4j
@Component
public class McpServerToolFilter implements McpToolFilter {

    @Value("${mcp.tool-filter.blocked-servers:}")
    private List<String> blockedServers;

    @Value("${mcp.tool-filter.blocked-tool-prefixes:}")
    private List<String> blockedToolPrefixes;

    /**
     * Spring AI MCP 會把 tool 一個一個 丟進這個方法檢查。
     * McpServerToolFilter 這個物件是被 Spring 管理的 bean；但 mcpConnectionInfo 和 tool 不是被注入成欄位，而是 Spring AI MCP 執行過濾時傳進 test() 的參數。
     */
    @Override
    public boolean test(McpConnectionInfo mcpConnectionInfo, McpSchema.Tool tool) {

        // 1. 從 mcpConnectionInfo 取得 MCP server 名稱
        String serverName = mcpConnectionInfo.initializeResult()
                .serverInfo()
                .name();

        // 2. 從 tool 取得 tool 名稱
        String toolName = tool.name();

        // 3. 輸出 log，表示正在檢查這個 tool
        log.info("驗證來自 MCP server: '{}' 的 tool: '{}'", serverName, toolName);

        // 4. 如果 MCP server 名稱包含設定檔中的封鎖關鍵字，則拒絕這個 tool
        Optional<String> blockedServer = findMatchedBlockedServer(serverName);
        if (blockedServer.isPresent()) {
            log.warn(
                    "工具 '{}' 已被拒絕，因為 MCP 伺服器 '{}' 符合封鎖設定 '{}'",
                    toolName, serverName, blockedServer.get()
            );
            return false; // 拒絕這個 tool
        }

        // 5. 如果 tool 名稱符合設定檔中的封鎖前綴，則拒絕這個 tool
        Optional<String> blockedToolPrefix = findMatchedBlockedToolPrefix(toolName);
        if (blockedToolPrefix.isPresent()) {
            log.warn(
                    "工具 '{}' 已被拒絕，因為它符合封鎖工具前綴 '{}'",
                    toolName, blockedToolPrefix.get()
            );
            return false; // 拒絕這個 tool
        }

        log.info(
                "工具 '{}' 已通過 MCP 伺服器 '{}' 的檢查",
                toolName, serverName
        );
        return true; // 允許這個 tool
    }

    // 搜尋封鎖的 server 名稱清單中，是否有任何一个與 MCP server 名稱匹配
    private Optional<String> findMatchedBlockedServer(String serverName) {

        // 1. 將 server 名稱轉換成小寫，以便不區分大小寫地比較
        String normalizedServerName = serverName.toLowerCase(Locale.ROOT);

        // 2. 逐一檢查每個封鎖的 server 名稱，看是否與 MCP server 名稱匹配
        return blockedServers.stream()
                .map(String::trim) // 3. 移除封鎖的 server 名稱前後的空白字元
                .filter(blockedServer -> !blockedServer.isEmpty()) // 4. 拒絕空字串
                .filter(blockedServer -> normalizedServerName.contains(blockedServer.toLowerCase(Locale.ROOT))) // 5. 檢查 MCP server 名稱是否包含封鎖的 server 名稱
                .findFirst();
    }

    // 搜尋封鎖的 tool 前綴清單中，是否有任何一个與 tool 名稱匹配
    private Optional<String> findMatchedBlockedToolPrefix(String toolName) {
        String normalizedToolName = toolName.toLowerCase(Locale.ROOT);

        return blockedToolPrefixes.stream()
                .map(String::trim)
                .filter(blockedToolPrefix -> !blockedToolPrefix.isEmpty())
                .filter(blockedToolPrefix -> normalizedToolName.startsWith(blockedToolPrefix.toLowerCase(Locale.ROOT)))
                .findFirst();
    }
}

/**
 * MCP server 啟動 / 連線
 * ↓
 * Spring AI MCP 向 server 查詢可用 tools
 * ↓
 * 拿到 tools 清單
 * ↓
 * 逐一呼叫 McpToolFilter.test(mcpConnectionInfo, tool)
 * ↓
 * true  -> 這個 tool 會被保留
 * false -> 這個 tool 不會暴露給 ChatClient / LLM 使用
 */
