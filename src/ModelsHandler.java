import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONObject;
import org.json.JSONArray;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ModelsHandler implements HttpHandler {
    private final ExecutorService executor = Executors.newFixedThreadPool(10);

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // Set CORS headers
        Headers responseHeaders = exchange.getResponseHeaders();
        responseHeaders.set("Access-Control-Allow-Origin", "*");
        responseHeaders.set("Access-Control-Allow-Credentials", "true");
        responseHeaders.set("Access-Control-Allow-Methods", "GET, OPTIONS");
        responseHeaders.set("Access-Control-Allow-Headers", "Content-Type, Authorization");
        responseHeaders.set("Content-Type", "application/json; charset=utf-8");
        responseHeaders.set("Cache-Control", "no-cache");
        responseHeaders.set("Connection", "keep-alive");
        String requestMethod = exchange.getRequestMethod().toUpperCase();

        if (requestMethod.equals("OPTIONS")) {
            // Handle preflight request
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (!requestMethod.equals("GET")) {
            // Only allow GET requests
            exchange.sendResponseHeaders(405, -1); // Method Not Allowed
            return;
        }

        // Handle request asynchronously
        executor.submit(() -> {
            try {
                List<JSONObject> fetchedModels = ModelService.models;
                // Read request headers
                Headers requestHeaders = exchange.getRequestHeaders();
                String authorizationHeader = requestHeaders.getFirst("Authorization");
                if(authorizationHeader != null){
                    // Get a valid short-term token
                    String tempToken = utils.getToken(authorizationHeader, exchange);
                    if (tempToken != null) {
                        fetchedModels = ModelService.fetchModels(tempToken);
                    }
                }

                // Convert the model list to JSON object
                JSONObject responseJson = new JSONObject();
                JSONArray dataArray = new JSONArray();
                for (JSONObject model : fetchedModels) {
                    dataArray.put(model);
                }
                responseJson.put("data", dataArray);
                responseJson.put("object", "list");

                // Prepare response
                byte[] responseBytes = responseJson.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, responseBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(responseBytes);
                }

            } catch (Exception e) {
                e.printStackTrace();
                sendError(exchange, "Internal Server Error: " + e.getMessage(), 500);
            }
        });
    }

    /**
     * Send error response
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
