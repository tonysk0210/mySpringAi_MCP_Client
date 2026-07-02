package com.example.myspringai_mcp_client.util;

import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.mcp.annotation.McpSampling;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 處理 MCP <b>取樣（sampling）</b>請求。當 MCP 伺服器上的工具呼叫
 * {@code ctx.sample(...)} 時，伺服器會將 {@link McpSchema.CreateMessageRequest}
 * 轉發給此客戶端。我們會針對自己的 LLM 執行所請求的補全（completion），並回傳
 * 結果，讓伺服器端的 LLM 得以使用，而不需要擁有自己的 API 金鑰。
 * <p>
 * 重要：我們注入的是低階的 {@link ChatModel}，而<b>不是</b> {@code ChatClient}。
 * {@code ChatClient} 已配置了 MCP 工具回呼（tool callbacks），若在此處使用可能會
 * 觸發另一次工具呼叫，進而發出另一個取樣請求 -> 造成無限迴圈。
 */
@Slf4j
@Component
public class HelpDeskSamplingProvider {

    private final ChatModel chatModel;

    public HelpDeskSamplingProvider(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @McpSampling(clients = "helpdesk-ticket-mcp-server-stdio")
    public McpSchema.CreateMessageResult handleSamplingRequest(McpSchema.CreateMessageRequest request) {
        log.info("收到來自伺服器的 MCP 取樣請求。系統提示詞：{}", request.systemPrompt());

        // 1. 將 MCP 的取樣訊息 sampling 轉換為 Spring AI 的提示詞訊息。
        List<Message> messages = new ArrayList<>();

        // 2. 將系統提示詞（如果有的話）添加到訊息清單中。 1 個 SystemMessage
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            messages.add(new SystemMessage(request.systemPrompt()));
        }

        // 3. 將使用者訊息添加到訊息清單中。1 個 UserMessage
        // 從 MCP request 裡面挑出「角色是 USER」而且「內容是文字」的訊息，把它們合併成一段文字，然後轉成 Spring AI 的 UserMessage。
        String userText = request.messages().stream()
                .filter(m -> m.content() instanceof McpSchema.TextContent
                        && m.role().name().equalsIgnoreCase(McpSchema.Role.USER.name()))
                .map(m -> ((McpSchema.TextContent) m.content()).text())
                .collect(Collectors.joining("\n"));

        messages.add(new UserMessage(userText));

        // 4. 直接使用 ChatModel 呼叫 LLM 以避免重新觸發 MCP 工具。
        // 這裡刻意使用 ChatModel，不是 ChatClient。原因註解裡有提到：ChatClient 可能已經接上 MCP tools，如果在 sampling 裡又用 ChatClient，
        // 可能導致 LLM 再次呼叫 MCP tool，tool 又再次發出 sampling request，形成無限循環。
        ChatResponse response = chatModel.call(new Prompt(messages));
        if (response.getResult() == null) {
            throw new IllegalStateException("LLM 未針對此 MCP 取樣請求回傳任何結果");
        }

        // 5. 從回應中提取生成的文本和模型名稱。
        String generatedText = Objects.requireNonNullElse(response.getResult().getOutput().getText(), "");
        String model = response.getMetadata().getModel();
        log.info("LLM 使用模型 '{}' 產生了取樣回應：{}", model, generatedText);

        // 6. 把 LLM 產生的文字包成 MCP protocol 需要的結果格式，角色是 ASSISTANT，然後回傳給 MCP server。
        return McpSchema.CreateMessageResult.builder(McpSchema.Role.ASSISTANT,generatedText,model)
                .build();
    }
}
/**
 * 1. Server 端呼叫 ctx.sample(spec -> ...)，把 systemPrompt 和 .message(...) 包成一個 CreateMessageRequest，透過 MCP 協定（stdio/JSON-RPC）送給連線的 client。
 * 2. Client 端因為有 @McpSampling(clients = "helpdesk-ticket-mcp-server-stdio") 這個註解，HelpDeskSamplingProvider.handleSamplingRequest(request) 就會被觸發，收到剛剛那個 CreateMessageRequest。
 * 3. Client 端跑完 LLM（chatModel.call(...)）後，組出：
 * return McpSchema.CreateMessageResult.builder(McpSchema.Role.ASSISTANT, generatedText, model)
 *         .build();
 * 3. 這個 CreateMessageResult 會沿著 MCP 協定原路送回 server 端。
 * 4. Server 端的 ctx.sample(...) 呼叫在這時候才真正返回——你拿到的 result 變數，內容就是 client 端 handleSamplingRequest 回傳的那個 CreateMessageResult（經過序列化/反序列化傳輸過來的等價物件，不是同一個 JVM 記憶體位址，但欄位內容完全一致）。
 *
 * 所以你在 server 端這樣取值：
 * String summary = ((McpSchema.TextContent) result.content()).text();
 * log.info("...client 使用的模型：{}", result.model());
 * - result.content() 對應到 client 端 builder 裡傳入的 generatedText（被包成 TextContent）。
 * - result.model() 對應到 client 端傳入的 model（也就是 response.getMetadata().getModel()）。
 * - result 的 role 則是 client 端寫死的 McpSchema.Role.ASSISTANT。
 *
 * 一句話總結：server 端的 result 就是 client 端 handleSamplingRequest 回傳值的「網路對面版本」。
 */
