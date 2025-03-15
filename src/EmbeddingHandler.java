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
    private final ExecutorService executor = Executors.newFixedThreadPool(10);

    /**
     * 可以在项目里全局复用一个 OkHttpClient，也可以这样在当前类中静态持有
     */
    private static final OkHttpClient okHttpClient = new OkHttpClient.Builder()
            // 设置连接、读取超时（可根据需要进行调整）
            .connectTimeout(java.time.Duration.ofSeconds(120))
            .readTimeout(java.time.Duration.ofSeconds(120))
            .build();

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

                // 发送 Embedding 请求至 GitHub Copilot API
                handleEmbeddingRequest(exchange, requestJson, receivedToken);

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
    private void handleEmbeddingRequest(HttpExchange exchange, JSONObject jsonBody, String receivedToken) throws IOException {
        // 准备 Headers
        Map<String, String> headers = HeadersInfo.getCopilotHeaders();
        // 加上用户传入的 Token
        headers.put("Authorization", "Bearer " + receivedToken);

        // 用 OkHttp 发起请求
        try (Response response = executeOkHttpRequest(headers, jsonBody)) {
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

    /**
     * 使用 OkHttp 发起 POST 请求并返回 Response
     */
    private Response executeOkHttpRequest(Map<String, String> headers, JSONObject jsonBody) throws IOException {
        // 构造 RequestBody
        RequestBody body = RequestBody.create(
                jsonBody.toString(),
                MediaType.parse("application/json; charset=utf-8")
        );

        // 构造请求
        Request.Builder requestBuilder = new Request.Builder()
                .url(COPILOT_CHAT_EMBEDDINGS_URL)
                .post(body);

        // 设置请求头
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            requestBuilder.addHeader(entry.getKey(), entry.getValue());
        }

        Request request = requestBuilder.build();

        // 同步调用
        return okHttpClient.newCall(request).execute();
    }
}
