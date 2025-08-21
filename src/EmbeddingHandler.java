import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class EmbeddingHandler implements HttpHandler {
    public static final String COPILOT_CHAT_EMBEDDINGS_URL = "https://api.individual.githubcopilot.com/embeddings";
    public static String getCopilotChatEmbeddingsUrl() {
        return COPILOT_CHAT_EMBEDDINGS_URL;
    }
    private final ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());




    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // Set CORS headers
        Headers responseHeaders = exchange.getResponseHeaders();
        responseHeaders.set("Access-Control-Allow-Origin", "*");
        responseHeaders.set("Access-Control-Allow-Credentials", "true");
        responseHeaders.set("Access-Control-Allow-Methods", "POST, OPTIONS");
        responseHeaders.set("Access-Control-Allow-Headers", "Content-Type, Authorization");
        responseHeaders.set("Content-Type", "application/json; charset=utf-8");
        responseHeaders.set("Cache-Control", "no-cache");
        responseHeaders.set("Connection", "keep-alive");

        String requestMethod = exchange.getRequestMethod().toUpperCase();

        if ("OPTIONS".equals(requestMethod)) {
            // Handle preflight request
            exchange.sendResponseHeaders(204, -1); // 204 No Content
            return;
        }

        if (!"POST".equals(requestMethod)) {
            // Only allow POST method
            sendErrorResponse(exchange, 405, "Method Not Allowed");
            return;
        }

        // Handle the request asynchronously
        executor.submit(() -> {
            try {
                Headers requestHeaders = exchange.getRequestHeaders();
                String authorizationHeader = requestHeaders.getFirst("Authorization");
                String receivedToken = utils.getToken(authorizationHeader, exchange);
                if (receivedToken == null || receivedToken.isEmpty()) {
                    utils.sendError(exchange, "Token is invalid.", 401);
                    return;
                }

                // Read the request body
                String requestBody = readRequestBody(exchange.getRequestBody());
                // Log the received JSON (formatted output)
                System.out.println("Received Embedding Request JSON:");
                System.out.println(requestBody);
                JSONObject requestJson = new JSONObject(requestBody);
                // 准备 Headers
                Map<String, String> headers = HeadersInfo.getCopilotHeaders(authorizationHeader.substring("Bearer ".length()));
                // 加上用户传入的 Token
                headers.put("Authorization", "Bearer " + receivedToken);
                headers.put("openai-intent","copilot-panel");
                // 发送 Embedding 请求至 GitHub Copilot API
                handleEmbeddingRequest(exchange, headers, requestJson);

            } catch (JSONException e) {
                e.printStackTrace();
                try {
                    sendErrorResponse(exchange, 400, "Invalid JSON format");
                } catch (IOException ioException) {
                    ioException.printStackTrace();
                }
            } catch (Exception e) {
                e.printStackTrace();
                try {
                    sendErrorResponse(exchange, 500, "Internal server error");
                } catch (IOException ioException) {
                    ioException.printStackTrace();
                }
            }
        });
    }

    /**
     * 发送错误响应
     */
    private void sendErrorResponse(HttpExchange exchange, int statusCode, String message) throws IOException {
        JSONObject errorJson = new JSONObject();
        errorJson.put("error", message);
        errorJson.put("code", statusCode);
        byte[] responseBytes = errorJson.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, responseBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
    }

    /**
     * 读取请求 Body
     */
    private String readRequestBody(InputStream is) throws IOException {
        String body = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                .lines().collect(Collectors.joining("\n"));
        is.close();
        return body;
    }

    /**
     * 辅助方法：格式化 JSON 便于日志输出
     */
    private String formatJson(String jsonString) {
        try {
            JSONObject json = new JSONObject(jsonString);
            return json.toString(4); // Indent with 4 spaces
        } catch (JSONException e) {
            try {
                JSONArray jsonArray = new JSONArray(jsonString);
                return jsonArray.toString(4);
            } catch (JSONException ex) {
                return jsonString; // 不可解析为JSON则直接返回原字符串
            }
        }
    }

    /**
     * 负责处理 Embedding 请求：使用 OkHttp 发送给 Copilot 并返回结果
     */
    private void handleEmbeddingRequest(HttpExchange exchange,  Map<String, String> headers,JSONObject jsonBody) throws IOException {

        // 用 OkHttp 发起请求
        try (Response response = utils.executeOkHttpRequest(headers, jsonBody,getCopilotChatEmbeddingsUrl())) {
            int responseCode = response.code();
            String responseBody = response.body() != null ? response.body().string() : "";

            // 打印/格式化日志
            System.out.println("Embedding Response from Copilot API:");
            System.out.println(formatJson(responseBody));

            if (response.isSuccessful()) {
                // 直接把返回结果写回给客户端
                byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, responseBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(responseBytes);
                }
            } else {
                // 出现非200时，返回错误信息
                System.err.println("Error: Received non-200 response from GitHub Copilot Embedding API.");
                sendErrorResponse(exchange, responseCode,
                        "Failed to get embeddings from Copilot API: " + responseBody);
            }
        }
    }

}
