import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * 处理聊天补全请求的处理器，适配 GitHub Copilot API，仅处理文本生成请求。
 */
public class CompletionHandler implements HttpHandler {
    private final HttpClient httpClient =  HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(60))
            .build();
    private final ExecutorService executor = Executors.newFixedThreadPool(10);
    private static final String COPILOT_CHAT_COMPLETIONS_URL = "https://api.individual.githubcopilot.com/chat/completions";

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // 设置 CORS 头
        Headers responseHeaders = exchange.getResponseHeaders();
        responseHeaders.add("Access-Control-Allow-Origin", "*");
        responseHeaders.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        responseHeaders.add("Access-Control-Allow-Headers", "Content-Type, Authorization");

        String requestMethod = exchange.getRequestMethod().toUpperCase();

        if (requestMethod.equals("OPTIONS")) {
            // 处理预检请求
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if ("GET".equals(requestMethod)) {
            // 返回欢迎页面
            String response = "<html><head><title>欢迎使用API</title></head><body><h1>欢迎使用API</h1><p>此 API 用于与 GitHub Copilot 模型交互。您可以发送消息给模型并接收响应。</p></body></html>";

            exchange.getResponseHeaders().add("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes(StandardCharsets.UTF_8));
            }
            return;
        }

        if (!"POST".equals(requestMethod)) {
            // 不支持的方法
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        // 异步处理请求
        CompletableFuture.runAsync(() -> {
            try {
                // 读取请求头
                Headers requestHeaders = exchange.getRequestHeaders();
                String authorizationHeader = requestHeaders.getFirst("Authorization");
                String receivedToken = utils.getToken(authorizationHeader,exchange);
                if (receivedToken == null || receivedToken.isEmpty()) {
                    sendError(exchange, "Token is invalid.", 401);
                    return;
                }
                // 读取请求体
                InputStream is = exchange.getRequestBody();
                String requestBody = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                        .lines()
                        .collect(Collectors.joining("\n"));

                JSONObject requestJson = new JSONObject(requestBody);

                // 提取参数
                String model = requestJson.optString("model", "gpt-4o");
                double temperature = requestJson.optDouble("temperature", 0.6);
                double topP = requestJson.optDouble("top_p", 0.9);
                int maxTokens = requestJson.optInt("max_tokens", 4096);
                boolean isStream = requestJson.optBoolean("stream", false);

                JSONArray messages = requestJson.optJSONArray("messages");
                if (messages == null || messages.isEmpty()) {
                    sendError(exchange, "消息内容为空。", 400);
                    return;
                }

                // 限制历史对话（最多100条）
                JSONArray limitedMessages = new JSONArray();
                int start = Math.max(0, messages.length() - 100);
                for (int i = start; i < messages.length(); i++) {
                    limitedMessages.put(messages.getJSONObject(i));
                }

                // 提取用户消息内容（假设最后一条是用户消息）
                String userContent = "";
                JSONObject lastMessage = limitedMessages.getJSONObject(limitedMessages.length() - 1);
                String role = lastMessage.optString("role", "");
                if (role.equals("user")) {
                    userContent = lastMessage.optString("content", "");
                }

                if (userContent.isEmpty()) {
                    sendError(exchange, "用户消息内容为空。", 400);
                    return;
                }

                // 构建新的请求 JSON，适配 Copilot API
                JSONObject newRequestJson = new JSONObject();
                newRequestJson.put("model", model);
                newRequestJson.put("max_tokens", maxTokens);
                newRequestJson.put("temperature", temperature);
                newRequestJson.put("top_p", topP);
                newRequestJson.put("stream", isStream);
                newRequestJson.put("messages", limitedMessages);

                // 构建 Copilot API 请求头
                Map<String, String> copilotHeaders = HeadersInfo.getCopilotHeaders();
                copilotHeaders.put("openai-intent","conversation-panel");
                copilotHeaders.put("Authorization", "Bearer " + receivedToken); // 更新 Token

                // 构建 HttpRequest
                HttpRequest request = buildHttpRequest(copilotHeaders, newRequestJson);
                System.out.println("请求体: \n" + newRequestJson.toString(4)+"\n");
                // 根据是否为流式响应，调用不同的处理方法
                if (isStream) {
                    handleStreamResponse(exchange, request);
                } else {
                    handleNormalResponse(exchange, request, model);
                }

            } catch (Exception e) {
                e.printStackTrace();
                sendError(exchange, "内部服务器错误: " + e.getMessage(), 500);
            }
        }, executor);
    }


    /**
     * 构建通用的 HttpRequest
     *
     * @param headers  请求头
     * @param jsonBody 请求体的 JSON 对象
     * @return 构建好的 HttpRequest 对象
     */
    private HttpRequest buildHttpRequest(Map<String, String> headers, JSONObject jsonBody) {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(CompletionHandler.COPILOT_CHAT_COMPLETIONS_URL))
                .header("Content-Type", HeadersInfo.content_type)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody.toString()));

        // 添加所有自定义头
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            requestBuilder.header(entry.getKey(), entry.getValue());
        }

        return requestBuilder.build();
    }

    /**
     * 处理流式响应
     *
     * @param exchange 当前的 HttpExchange 对象
     * @param request  构建好的 HttpRequest 对象
     */
    private void handleStreamResponse(HttpExchange exchange, HttpRequest request) throws IOException {
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofLines())
                .thenAccept(response -> {
                    try {
                        if (response.statusCode() != 200) {
                            sendError(exchange, "API 错误: " + response.statusCode(), 502);
                            return;
                        }

                        Headers responseHeaders = exchange.getResponseHeaders();
                        responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8");
                        responseHeaders.add("Cache-Control", "no-cache");
                        responseHeaders.add("Connection", "keep-alive");
                        exchange.sendResponseHeaders(200, 0);

                        try (OutputStream os = exchange.getResponseBody()) {
                            response.body().forEach(line -> {
                                // System.out.println("原始行: " + line);
                                if (line.startsWith("data: ")) {
                                    String data = line.substring(6).trim();
                                    if (data.equals("[DONE]")) {
                                        try {
                                            // 转发 [DONE] 信号
                                            os.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                                            os.flush();
                                        } catch (IOException e) {
                                            e.printStackTrace();
                                        }
                                        return;
                                    }

                                    try {
                                        JSONObject sseJson = new JSONObject(data);

                                        // 检查是否包含 'choices' 数组
                                        if (sseJson.has("choices")) {
                                            JSONArray choices = sseJson.getJSONArray("choices");
                                            for (int i = 0; i < choices.length(); i++) {
                                                JSONObject choice = choices.getJSONObject(i);
                                                JSONObject delta = choice.optJSONObject("delta");
                                                if (delta != null && delta.has("content")) {
                                                    Object contentObj = delta.get("content");
                                                    String content = "";
                                                    if (!delta.isNull("content")) {
                                                        content = delta.getString("content");
                                                    }

                                                    // 仅在content不为空时处理
                                                    if (!content.isEmpty()) {
                                                        // 构建新的 SSE JSON
                                                        JSONObject newSseJson = new JSONObject();
                                                        JSONArray newChoices = new JSONArray();
                                                        JSONObject newChoice = new JSONObject();
                                                        newChoice.put("index", choice.optInt("index", i));

                                                        // 添加 'content' 字段
                                                        JSONObject newDelta = new JSONObject();
                                                        newDelta.put("content", content);
                                                        System.out.print(content);
                                                        newChoice.put("delta", newDelta);

                                                        newChoices.put(newChoice);
                                                        newSseJson.put("choices", newChoices);

                                                        // 添加其他字段
                                                        if (sseJson.has("created")) {
                                                            newSseJson.put("created", sseJson.getLong("created"));
                                                        } else {
                                                            newSseJson.put("created", Instant.now().getEpochSecond());
                                                        }

                                                        if (sseJson.has("id")) {
                                                            newSseJson.put("id", sseJson.getString("id"));
                                                        } else {
                                                            newSseJson.put("id", UUID.randomUUID().toString());
                                                        }

                                                        newSseJson.put("model", sseJson.optString("model", "gpt-3.5-turbo"));
                                                        newSseJson.put("system_fingerprint", "fp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));

                                                        // 构建新的 SSE 行
                                                        String newSseLine = "data: " + newSseJson + "\n\n";
                                                        os.write(newSseLine.getBytes(StandardCharsets.UTF_8));
                                                        os.flush();
                                                    }
                                                }
                                            }
                                        }
                                    } catch (JSONException e) {
                                        System.err.println("JSON解析错误: " + e.getMessage());
                                    } catch (IOException e) {
                                        System.err.println("响应发送失败: " + e.getMessage());
                                        e.printStackTrace();
                                    }
                                }
                            });
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                        sendError(exchange, "响应发送失败: " + e.getMessage(), 502);
                    }
                })
                .exceptionally(ex -> {
                    ex.printStackTrace();
                    sendError(exchange, "请求失败: " + ex.getMessage(), 502);
                    return null;
                });
    }

    /**
     * 处理非流式响应
     *
     * @param exchange 当前的 HttpExchange 对象
     * @param request  构建好的 HttpRequest 对象
     * @param model    使用的模型名称
     */
    private void handleNormalResponse(HttpExchange exchange, HttpRequest request, String model) {
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    try {
                        if (response.statusCode() != 200) {
                            sendError(exchange, "API 错误: " + response.statusCode(), 502);
                            return;
                        }

                        // 解析Copilot的响应
                        JSONObject responseJson = new JSONObject(response.body());
                        JSONArray choices = responseJson.optJSONArray("choices");
                        String assistantContent = "";
                        if (choices != null && choices.length() > 0) {
                            JSONObject firstChoice = choices.getJSONObject(0);
                            if (firstChoice.has("message")) {
                                JSONObject message = firstChoice.getJSONObject("message");
                                if (!message.isNull("content")) {
                                    assistantContent = message.optString("content", "");
                                }
                            }
                        }

                        // 构建 OpenAI API 风格的响应 JSON
                        JSONObject openAIResponse = new JSONObject();
                        openAIResponse.put("id", "chatcmpl-" + UUID.randomUUID().toString());
                        openAIResponse.put("object", "chat.completion");
                        openAIResponse.put("created", Instant.now().getEpochSecond());
                        openAIResponse.put("model", model);

                        JSONArray choicesArray = new JSONArray();
                        JSONObject choiceObject = new JSONObject();
                        choiceObject.put("index", 0);

                        JSONObject messageObject = new JSONObject();
                        messageObject.put("role", "assistant");
                        messageObject.put("content", assistantContent);
                        System.out.println("Received: \n"+assistantContent);

                        choiceObject.put("message", messageObject);
                        choiceObject.put("finish_reason", "stop");
                        choicesArray.put(choiceObject);

                        openAIResponse.put("choices", choicesArray);

                        exchange.getResponseHeaders().add("Content-Type", "application/json");
                        String responseBody = openAIResponse.toString();
                        exchange.sendResponseHeaders(200, responseBody.getBytes(StandardCharsets.UTF_8).length);
                        try (OutputStream os = exchange.getResponseBody()) {
                            os.write(responseBody.getBytes(StandardCharsets.UTF_8));
                        }

                    } catch (Exception e) {
                        e.printStackTrace();
                        sendError(exchange, "处理响应时发生错误: " + e.getMessage(), 500);
                    }
                })
                .exceptionally(ex -> {
                    ex.printStackTrace();
                    sendError(exchange, "请求失败: " + ex.getMessage(), 502);
                    return null;
                });
    }
    /**
     * 发送错误响应
     */
    public static void sendError(HttpExchange exchange, String message, int HTTP_code) {
        try {
            JSONObject error = new JSONObject();
            error.put("error", message);
            byte[] bytes = error.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(HTTP_code, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
