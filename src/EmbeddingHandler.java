import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class EmbeddingHandler implements HttpHandler {
    public static final String COPILOT_CHAT_EMBEDDINGS_URL = "https://api.individual.githubcopilot.com/embeddings";
    private final ExecutorService executor = Executors.newFixedThreadPool(10);
    private static final Logger logger = LogManager.getLogger(EmbeddingHandler.class);

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

                // Extract necessary fields
                EmbeddingParameters params = extractEmbeddingParameters(requestJson);

                // Send request to GitHub Copilot API and get response
                handleEmbeddingRequest(exchange, params, receivedToken);

            } catch (JSONException e) {
                logger.error(e.getMessage());
                try {
                    sendErrorResponse(exchange, 400, "Invalid JSON format");
                } catch (IOException ioException) {
                    logger.error(ioException.getMessage());
                }
            } catch (Exception e) {
                logger.error(e.getMessage());
                try {
                    sendErrorResponse(exchange, 500, "Internal server error");
                } catch (IOException ioException) {
                    logger.error(ioException.getMessage());
                }
            }
        });
    }

    /**
     * Send error response
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
     * Read the request body and return as a string
     */
    private String readRequestBody(InputStream is) throws IOException {
        String body = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                .lines().collect(Collectors.joining("\n"));
        is.close();
        return body;
    }

    /**
     * Format JSON string for printing
     */
    private String formatJson(String jsonString) {
        try {
            JSONObject json = new JSONObject(jsonString);
            return json.toString(4); // Indent with 4 spaces
        } catch (JSONException e) {
            try {
                JSONArray jsonArray = new JSONArray(jsonString);
                return jsonArray.toString(4); // Indent with 4 spaces
            } catch (JSONException ex) {
                return jsonString; // Not a valid JSON, return the original string
            }
        }
    }

    /**
     * Extract necessary parameters from the embedding request
     */
    private EmbeddingParameters extractEmbeddingParameters(JSONObject requestJson) throws JSONException {
        // Extract "model"
        String model = requestJson.optString("model", "text-embedding-3-small"); // Default to "text-embedding-3-small"

        // Verify model exists and type matches
        for (JSONObject m : ModelService.models) {
            if (m.getString("id").equals(model) &&
                    m.getJSONObject("capabilities").getString("type").equals("embeddings")) {
                break;
            }else {
                model = "text-embedding-3-small";
            }
        }
        // Extract "input"
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
            // Input format is incorrect
            throw new JSONException("Invalid input format");
        }

        // Check if inputs are empty
        if (inputs.isEmpty()) {
            throw new JSONException("Input cannot be empty.");
        }

        // Extract "user" (optional)
        String user = requestJson.optString("user", "");

        return new EmbeddingParameters(model, inputs);
    }

    /**
     * Handle embedding request
     */
    private void handleEmbeddingRequest(HttpExchange exchange, EmbeddingParameters params, String receivedToken) throws IOException {
        // Set request headers
        Map<String, String> headers = HeadersInfo.getCopilotHeaders();
        headers.put("Authorization", "Bearer " + receivedToken); // Update Token

        // Build the request body
        JSONObject jsonBody = constructEmbeddingRequestBody(params);

        // Build and send HttpURLConnection
        HttpURLConnection connection = createConnection(headers, jsonBody);
        int responseCode = connection.getResponseCode();
        String responseBody = readStream(connection.getInputStream());
        System.out.println(formatJson(responseBody));

        if (responseCode == HttpURLConnection.HTTP_OK) {
            // Return response body
            System.out.println("Received Embedding Response from Copilot API:");
            System.out.println(formatJson(responseBody));

            // Directly return Copilot's response
            byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        } else {
            // Handle non-200 response
            System.err.println("Error: Received non-200 response from GitHub Copilot Embedding API.");
            // Read error stream
            String errorResponse = readStream(connection.getErrorStream());
            sendErrorResponse(exchange, responseCode, "Failed to get embeddings from Copilot API: " + errorResponse);
        }
    }

    /**
     * Construct the request body for OpenAI Embedding API
     */
    private JSONObject constructEmbeddingRequestBody(EmbeddingParameters params) {
        JSONObject jsonBody = new JSONObject();
        JSONArray inputArray = new JSONArray();

        for (String input : params.inputs) {
            inputArray.put(input);
        }

        jsonBody.put("model", params.model);
        jsonBody.put("input", inputArray);

        return jsonBody;
    }

    /**
     * Create and configure HttpURLConnection
     */
    private HttpURLConnection createConnection(Map<String, String> headers, JSONObject jsonBody) throws IOException {
        URL url = new URL(EmbeddingHandler.COPILOT_CHAT_EMBEDDINGS_URL);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(120000); // 120 seconds
        connection.setReadTimeout(120000); // 120 seconds
        connection.setDoOutput(true);

        // Set request headers
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            connection.setRequestProperty(entry.getKey(), entry.getValue());
        }

        // Write request body
        try (OutputStream os = connection.getOutputStream()) {
            byte[] input = jsonBody.toString().getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        return connection;
    }

    /**
     * Read input stream content as a string
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
     * Inner class for storing embedding request parameters
     */
    private static class EmbeddingParameters {
        String model;
        List<String> inputs;

        public EmbeddingParameters(String model, List<String> inputs) {
            this.model = model;
            this.inputs = inputs;
        }
    }

}
