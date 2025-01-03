import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class CompletionHandler implements HttpHandler {
    private static final String COPILOT_CHAT_COMPLETIONS_URL = "https://api.individual.githubcopilot.com/chat/completions";
    private final ExecutorService executor = Executors.newFixedThreadPool(10);

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
        executor.submit(() -> {
            try {
                // 读取请求头
                Headers requestHeaders = exchange.getRequestHeaders();
                String authorizationHeader = requestHeaders.getFirst("Authorization");
                String receivedToken = utils.getToken(authorizationHeader, exchange);
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
                boolean isO1 = false;
                if (model.startsWith("o1")) {
                    newRequestJson.put("stream", false);
                    isO1 = true;
                } else {
                    newRequestJson.put("stream", isStream);
                }
                newRequestJson.put("max_tokens", maxTokens);
                newRequestJson.put("temperature", temperature);
                newRequestJson.put("top_p", topP);

                newRequestJson.put("messages", limitedMessages);

                // 构建 Copilot API 请求头
                Map<String, String> copilotHeaders = HeadersInfo.getCopilotHeaders();
                copilotHeaders.put("openai-intent", "conversation-panel");
                copilotHeaders.put("Authorization", "Bearer " + receivedToken); // 更新 Token

                // 根据是否为流式响应，调用不同的处理方法
                if (isStream) {
                    if (!isO1) {
                        handleStreamResponse(exchange, copilotHeaders, newRequestJson);
                    } else {
                        handleO1StreamResponse(exchange, copilotHeaders, newRequestJson, model);
                    }
                } else {
                    handleNormalResponse(exchange, copilotHeaders, newRequestJson, model);
                }

            } catch (Exception e) {
                e.printStackTrace();
                sendError(exchange, "内部服务器错误: " + e.getMessage(), 500);
            }
        });
    }

    private void handleO1StreamResponse(HttpExchange exchange, Map<String, String> headers, JSONObject requestJson, String model) {
        try {
            HttpURLConnection connection = createConnection(headers, requestJson);
            int responseCode = connection.getResponseCode();

            if (responseCode != HttpURLConnection.HTTP_OK) {
                String errorResponse = readStream(connection.getErrorStream());
                sendError(exchange, "API 错误: " + responseCode + " - " + errorResponse, responseCode);
                return;
            }

            String responseBody = readStream(connection.getInputStream());
            JSONObject responseJson = new JSONObject(responseBody);
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
            openAIResponse.put("model", responseJson.optString("model", "gpt-4o"));

            JSONArray choicesArray = new JSONArray();
            JSONObject choiceObject = new JSONObject();
            choiceObject.put("index", 0);

            JSONObject messageObject = new JSONObject();
            messageObject.put("role", "assistant");
            messageObject.put("content", assistantContent);
            System.out.println("Received: \n" + assistantContent);

            choiceObject.put("message", messageObject);
            choiceObject.put("finish_reason", "stop");
            choicesArray.put(choiceObject);

            openAIResponse.put("choices", choicesArray);

            exchange.getResponseHeaders().add("Content-Type", "application/json");
            String responseStr = openAIResponse.toString();
            exchange.sendResponseHeaders(200, responseStr.getBytes(StandardCharsets.UTF_8).length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseStr.getBytes(StandardCharsets.UTF_8));
            }

        } catch (Exception e) {
            e.printStackTrace();
            sendError(exchange, "处理响应时发生错误: " + e.getMessage(), 500);
        }
    }

    /**
     * 处理流式响应
     */
    private void handleStreamResponse(HttpExchange exchange, Map<String, String> headers, JSONObject requestJson) throws IOException {
        HttpURLConnection connection = createConnection(headers, requestJson);
        int responseCode = connection.getResponseCode();

        if (responseCode != HttpURLConnection.HTTP_OK) {
            String errorResponse = readStream(connection.getErrorStream());
            sendError(exchange, "API 错误: " + responseCode + " - " + errorResponse, responseCode);
            return;
        }

        exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().add("Cache-Control", "no-cache");
        exchange.getResponseHeaders().add("Connection", "keep-alive");
        exchange.sendResponseHeaders(200, 0);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
             OutputStream os = exchange.getResponseBody()) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data: ")) {
                    String data = line.substring(6).trim();
                    if (data.equals("[DONE]")) {
                        os.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                        os.flush();
                        break;
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
                                    String content = delta.optString("content", "");

                                    // 仅在content不为空时处理
                                    if (!content.isEmpty()) {
                                        // 构建新的 SSE JSON
                                        JSONObject newSseJson = new JSONObject();
                                        JSONArray newChoices = new JSONArray();
                                        JSONObject newChoice = new JSONObject();
                                        newChoice.put("index", choice.optInt("index", i));

                                        JSONObject newDelta = new JSONObject();
                                        newDelta.put("content", content);
                                        System.out.print(content);
                                        newChoice.put("delta", newDelta);

                                        newChoices.put(newChoice);
                                        newSseJson.put("choices", newChoices);

                                        // 添加其他字段

                                        newSseJson.put("created", sseJson.optLong("created", Instant.now().getEpochSecond()));
                                        newSseJson.put("id", sseJson.optString("id", UUID.randomUUID().toString()));
                                        newSseJson.put("model", sseJson.optString("model", requestJson.optString("model")));
                                        newSseJson.put("system_fingerprint", sseJson.optString("system_fingerprint", "fp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)));

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
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
            sendError(exchange, "响应发送失败: " + e.getMessage(), 502);
        }
    }

    /**
     * 处理非流式响应
     */
    private void handleNormalResponse(HttpExchange exchange, Map<String, String> headers, JSONObject requestJson, String model) {
        try {
            HttpURLConnection connection = createConnection(headers, requestJson);
            int responseCode = connection.getResponseCode();

            if (responseCode != HttpURLConnection.HTTP_OK) {
                String errorResponse = readStream(connection.getErrorStream());
                sendError(exchange, "API 错误: " + responseCode + " - " + errorResponse, responseCode);
                return;
            }

            String responseBody = readStream(connection.getInputStream());
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBody.getBytes(StandardCharsets.UTF_8).length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBody.getBytes(StandardCharsets.UTF_8));
            }

        } catch (Exception e) {
            e.printStackTrace();
            sendError(exchange, "处理响应时发生错误: " + e.getMessage(), 500);
        }
    }

    /**
     * 创建并配置 HttpURLConnection
     */
    private HttpURLConnection createConnection(Map<String, String> headers, JSONObject jsonBody) throws IOException {
        URL url = new URL(CompletionHandler.COPILOT_CHAT_COMPLETIONS_URL);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(60000); // 60秒
        connection.setReadTimeout(60000); // 60秒
        connection.setDoOutput(true);

        // 设置请求头
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            connection.setRequestProperty(entry.getKey(), entry.getValue());
        }

        // 写入请求体
        try (OutputStream os = connection.getOutputStream()) {
            byte[] input = jsonBody.toString().getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        return connection;
    }

    /**
     * 读取输入流内容为字符串
     */
    private String readStream(InputStream is) throws IOException {
        if (is == null) return "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }
        reader.close();
        return sb.toString();
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
