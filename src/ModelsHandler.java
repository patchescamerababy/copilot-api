import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 处理获取模型列表请求的处理器，适配 GitHub Copilot API，仅处理 GET 请求。
 */
public class ModelsHandler implements HttpHandler {

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ExecutorService executor = Executors.newFixedThreadPool(10);
    private static final String COPILOT_MODELS_URL = "https://api.individual.githubcopilot.com/models";

    // 初始化 TokenManager 和 Utils
    private final TokenManager tokenManager = new TokenManager();
//    private final Utils utils = new Utils();

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // 设置 CORS 头
        Headers responseHeaders = exchange.getResponseHeaders();
        responseHeaders.add("Access-Control-Allow-Origin", "*");
        responseHeaders.add("Access-Control-Allow-Methods", "GET, OPTIONS");
        responseHeaders.add("Access-Control-Allow-Headers", "Content-Type, Authorization");

        String requestMethod = exchange.getRequestMethod().toUpperCase();

        if (requestMethod.equals("OPTIONS")) {
            // 处理预检请求
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (!requestMethod.equals("GET")) {
            // 仅允许 GET 请求
            exchange.sendResponseHeaders(405, -1); // Method Not Allowed
            return;
        }

        // 异步处理请求
        CompletableFuture.runAsync(() -> {
            try {
                List<JSONObject> fetchedModels = ModelService.models;
                // 读取请求头
                Headers requestHeaders = exchange.getRequestHeaders();
                String authorizationHeader = requestHeaders.getFirst("Authorization");
                // 获取有效的短期令牌
                String tempToken = utils.getToken(authorizationHeader,exchange);

                if (tempToken != null || !tempToken.isEmpty()) {
                    fetchedModels = ModelService.fetchModels(tempToken);
                }


                // 使用临时令牌获取模型列表

                if (fetchedModels == null) {
                    sendError(exchange, "无法获取模型列表。", 500);
                    return;
                }

                // 将模型列表转换为 JSON 对象
                JSONObject responseJson = new JSONObject();
                responseJson.put("data", fetchedModels);
                responseJson.put("object", "list");

                // 准备响应
                byte[] responseBytes = responseJson.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, responseBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(responseBytes);
                }

            } catch (Exception e) {
                e.printStackTrace();
                sendError(exchange, "内部服务器错误: " + e.getMessage(), 500);
            }
        }, executor);
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
