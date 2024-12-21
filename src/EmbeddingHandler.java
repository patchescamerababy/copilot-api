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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class EmbeddingHandler implements HttpHandler {
    public static final String COPILOT_CHAT_EMBEDDINGS_URL = "https://api.individual.githubcopilot.com/embeddings";
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(60))
            .build();
    private final ExecutorService executor = Executors.newFixedThreadPool(10);

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // 设置CORS头
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
            // 处理预检请求
            exchange.sendResponseHeaders(204, -1); // 204 No Content
            return;
        }

        if (!"POST".equals(requestMethod)) {
            // 仅允许POST方法
            sendErrorResponse(exchange, 405, "Method Not Allowed");
            return;
        }

        // 异步处理请求
        CompletableFuture.runAsync(() -> {
            try {
                Headers requestHeaders = exchange.getRequestHeaders();
                String authorizationHeader = requestHeaders.getFirst("Authorization");
                String receivedToken = utils.getToken(authorizationHeader,exchange);
                // 读取请求体
                String requestBody = readRequestBody(exchange.getRequestBody());
                // 记录收到的JSON（格式化输出）
                System.out.println("Received Embedding Request JSON:");
                System.out.println(requestBody);
                JSONObject requestJson = new JSONObject(requestBody);

                // 提取必要的字段
                EmbeddingParameters params = extractEmbeddingParameters(requestJson);

                // 发送请求到GitHub Copilot API并获取响应
                handleEmbeddingRequest(exchange, params, receivedToken);

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
        }, executor);
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
     * 读取请求体并返回为字符串
     */
    private String readRequestBody(InputStream is) throws IOException {
        String body = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                .lines().collect(Collectors.joining("\n"));
        is.close();
        return body;
    }

    /**
     * 格式化JSON字符串以便打印
     */
    private String formatJson(String jsonString) {
        try {
            JSONObject json = new JSONObject(jsonString);
            return json.toString(4); // 缩进4个空格
        } catch (JSONException e) {
            try {
                JSONArray jsonArray = new JSONArray(jsonString);
                return jsonArray.toString(4); // 缩进4个空格
            } catch (JSONException ex) {
                return jsonString; // 不是有效的JSON，返回原始字符串
            }
        }
    }

    /**
     * 提取嵌入请求中的必要参数
     */
    private EmbeddingParameters extractEmbeddingParameters(JSONObject requestJson) throws JSONException {
        // 提取 "model"
        String model = requestJson.optString("model", "text-embedding-3-small"); // 默认使用 "text-embedding-3-small"

        // 验证模型是否存在并且类型匹配
        boolean modelValid = false;
        for (JSONObject m : ModelService.models) {
            if (m.getString("id").equals(model) &&
                    m.getJSONObject("capabilities").getString("type").equals("embeddings")) {
                modelValid = true;
                break;
            }
        }

        if (!modelValid) {
            System.out.println("Invalid or unsupported embedding model received: " + model + ". Falling back to default model.");
            model = "text-embedding-3-small"; // 回落到默认模型
        }

        // 提取 "input"
        Object inputObj = requestJson.opt("input");
        List<String> inputs = new ArrayList<>();
        if (inputObj instanceof JSONArray) {
            JSONArray inputArray = (JSONArray) inputObj;
            for (int i = 0; i < inputArray.length(); i++) {
                inputs.add(inputArray.getString(i));
            }
        } else if (inputObj instanceof String) {
            inputs.add((String) inputObj);
        } else {
            // 输入格式不正确
            throw new JSONException("Invalid input format");
        }

        // 检查输入是否为空
        if (inputs.isEmpty()) {
            throw new JSONException("Input cannot be empty.");
        }

        // 提取 "user" (可选)
        String user = requestJson.optString("user", "");

        return new EmbeddingParameters(model, inputs, user);
    }

    /**
     * 处理嵌入请求
     */
    private void handleEmbeddingRequest(HttpExchange exchange, EmbeddingParameters params, String receivedToken) throws IOException, InterruptedException {
        // 请求头设置
        Map<String, String> headers = HeadersInfo.getCopilotHeaders();
        headers.put("Authorization", "Bearer " + receivedToken); // 更新 Token

        // 构建请求体
        JSONObject jsonBody = constructEmbeddingRequestBody(params);

        // 构建HttpRequest
        HttpRequest request = buildHttpRequest(headers, jsonBody);

        // 发送请求并获取响应
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // 输出响应状态码和响应体（格式化输出）
        System.out.println("GitHub Copilot Embedding API Response Status Code: " + response.statusCode());
        System.out.println("GitHub Copilot Embedding API Response Body:");
        System.out.println(formatJson(response.body()));

        if (response.statusCode() == 200) {
            // 返回响应体
            String copilotResponse = response.body();
            System.out.println("Received Embedding Response from Copilot API:");
            System.out.println(formatJson(copilotResponse));

            // 直接返回Copilot的响应
            byte[] responseBytes = copilotResponse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        } else {
            // 处理非200响应
            System.err.println("Error: Received non-200 response from GitHub Copilot Embedding API.");
            // 发送错误响应
            sendErrorResponse(exchange, response.statusCode(), "Failed to get embeddings from Copilot API");
        }
    }

    /**
     * 构建OpenAI Embedding API的请求体
     */
    private JSONObject constructEmbeddingRequestBody(EmbeddingParameters params) {
        JSONObject jsonBody = new JSONObject();
        JSONArray inputArray = new JSONArray();

        for (String input : params.inputs) {
            inputArray.put(input);
        }

        jsonBody.put("model", params.model);
        jsonBody.put("input", inputArray);

        if (!params.user.isEmpty()) {
            jsonBody.put("user", params.user);
        }

        return jsonBody;
    }

    /**
     * 构建HttpRequest
     */
    private HttpRequest buildHttpRequest(Map<String, String> headers, JSONObject jsonBody) {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(EmbeddingHandler.COPILOT_CHAT_EMBEDDINGS_URL))
                .header("Content-Type", HeadersInfo.content_type)
                .timeout(Duration.ofMinutes(2))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody.toString()));

        // 添加所有自定义头（除去受限头部）
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            requestBuilder.header(entry.getKey(), entry.getValue());
        }

        return requestBuilder.build();
    }

    /**
     * 内部类用于存储嵌入请求参数
     */
    private static class EmbeddingParameters {
        String model;
        List<String> inputs;
        String user;

        public EmbeddingParameters(String model, List<String> inputs, String user) {
            this.model = model;
            this.inputs = inputs;
            this.user = user;
        }
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
