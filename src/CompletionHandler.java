import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

/**
 * Handler for chat completion requests, adapting the GitHub Copilot API, only handling text generation requests.
 */
public class CompletionHandler implements HttpHandler {
    private static final String COPILOT_CHAT_COMPLETIONS_URL = "https://api.individual.githubcopilot.com/chat/completions";
    private final ExecutorService executor = Executors.newFixedThreadPool(10);

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // Set CORS headers
        Headers responseHeaders = exchange.getResponseHeaders();
        responseHeaders.set("Access-Control-Allow-Origin", "*");
        responseHeaders.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        responseHeaders.set("Access-Control-Allow-Headers", "Content-Type, Authorization");
        responseHeaders.set("Connection", "keep-alive");
        String requestMethod = exchange.getRequestMethod().toUpperCase();

        if (requestMethod.equals("OPTIONS")) {
            // Handle preflight requests
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if ("GET".equals(requestMethod)) {
            // Return welcome page
            String response = "<html><head><title>Welcome to API</title>" +
                    "</head>" +
                    "<body>" +
                    "<h1>Welcome to API</h1>" +
                    "<p>This API is used to interact with the GitHub Copilot model. You can send messages to the model and receive responses.</p>" +
                    "</body>" +
                    "</html>";

            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes(StandardCharsets.UTF_8));
            }
            return;
        }

        if (!"POST".equals(requestMethod)) {
            // Method not supported
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        // Asynchronously handle the request
        executor.submit(() -> {
            try {
                // Read request headers
                Headers requestHeaders = exchange.getRequestHeaders();
                String authorizationHeader = requestHeaders.getFirst("Authorization");
                if (!authorizationHeader.startsWith("Bearer ")) {
                    utils.sendError(exchange, "Token is invalid.", 401);
                    return;
                }
                String receivedToken = utils.getToken(authorizationHeader, exchange);
                if (receivedToken == null || receivedToken.isEmpty()) {
                    utils.sendError(exchange, "Token is invalid.", 401);
                    return;
                }
                // Read request body
                InputStream is = exchange.getRequestBody();
                String requestBody = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                        .lines()
                        .collect(Collectors.joining("\n"));

                JSONObject requestJson = new JSONObject(requestBody);

                // Extract parameters
                String model = requestJson.optString("model", requestJson.getString("model"));
                boolean isStream = requestJson.optBoolean("stream", false);

                // Build a new request JSON, adapting to the Copilot API
                boolean isO1 = false;
                if (model.startsWith("o1") || model.startsWith("o3")) {
                    System.out.println("stream: false");
                    isO1 = true;
                } else {
                    requestJson.put("stream", isStream);
                }
                boolean hasImage = false;
                JSONArray messages = requestJson.optJSONArray("messages");
                if (messages != null) {

                    Iterator<Object> iterator = messages.iterator();
                    while (iterator.hasNext()) {
                        JSONObject message = (JSONObject) iterator.next();
                        if (message.has("content")) {
                            Object contentObj = message.get("content");
                            if (contentObj instanceof JSONArray) {
                                JSONArray contentArray = (JSONArray) contentObj;

                                for (int j = 0; j < contentArray.length(); j++) {
                                    JSONObject contentItem = contentArray.getJSONObject(j);
                                    if (contentItem.has("type")) {
                                        if (contentItem.getString("type").equals("image_url") && contentItem.has("image_url")) {
                                           hasImage=true;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Map<String, String> copilotHeaders = HeadersInfo.getCopilotHeaders();
                copilotHeaders.put("openai-intent", "conversation-panel");
                copilotHeaders.put("copilot-vision-request", hasImage ? "true" : "false");
                copilotHeaders.put("Authorization", "Bearer " + receivedToken); // Update Token
                System.out.println(requestJson.toString(4));
                // Depending on whether it is a stream response, call different handling methods
                if (isStream) {
                    if (!isO1) {
                        handleStreamResponse(exchange, copilotHeaders, requestJson);
                    } else {
                        handleO1StreamResponse(exchange, copilotHeaders, requestJson); // Only O1 Series change requestJson
                    }
                } else {
                    handleNormalResponse(exchange, copilotHeaders, requestJson);
                }

            } catch (Exception e) {
                e.printStackTrace();
                utils.sendError(exchange, "Internal server error: " + e.getMessage(), 500);
            }
        });
    }

    private void handleO1StreamResponse(HttpExchange exchange, Map<String, String> headers, JSONObject requestJson) {
        try {
            HttpURLConnection connection = createConnection(headers, requestJson);
            int responseCode = connection.getResponseCode();

            if (responseCode != HttpURLConnection.HTTP_OK) {
                String errorResponse = readStream(connection.getErrorStream());
                utils.sendError(exchange, errorResponse, responseCode);
                return;
            }

            String responseBody = readStream(connection.getInputStream());
            JSONObject responseJson = new JSONObject(responseBody);
            JSONArray choices = responseJson.optJSONArray("choices");
            String assistantContent = "";
            if (choices != null && !choices.isEmpty()) {
                JSONObject firstChoice = choices.getJSONObject(0);
                if (firstChoice.has("message")) {
                    JSONObject message = firstChoice.getJSONObject("message");
                    if (!message.isNull("content")) {
                        assistantContent = message.optString("content", "");
                    }
                }
            }

            // Build OpenAI API style response JSON
            JSONObject openAIResponse = new JSONObject();
            openAIResponse.put("id", "chatcmpl-" + UUID.randomUUID());
            openAIResponse.put("object", "chat.completion");
            openAIResponse.put("created", Instant.now().getEpochSecond());
            openAIResponse.put("model", responseJson.optString("model", responseJson.optString("model", "o1")));
            openAIResponse.put("system_fingerprint", openAIResponse.optString("system_fingerprint", "fp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)));

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
            byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }

        } catch (Exception e) {
            e.printStackTrace();
            utils.sendError(exchange, "Error occurred while processing response: " + e.getMessage(), 500);
        }
    }

    /**
     * Handle stream response
     */
    private void handleStreamResponse(HttpExchange exchange, Map<String, String> headers, JSONObject requestJson) throws IOException {
        HttpURLConnection connection = createConnection(headers, requestJson);
        int responseCode = connection.getResponseCode();

        if (responseCode != HttpURLConnection.HTTP_OK) {
            String errorResponse = readStream(connection.getErrorStream());
            utils.sendError(exchange, errorResponse, responseCode);
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

                        // Check if it contains 'choices' array
                        if (sseJson.has("choices")) {
                            JSONArray choices = sseJson.getJSONArray("choices");
                            for (int i = 0; i < choices.length(); i++) {
                                JSONObject choice = choices.getJSONObject(i);
                                JSONObject delta = choice.optJSONObject("delta");
                                if (delta != null && delta.has("content")) {
                                    String content = delta.optString("content", "");

                                    // Only process if content is not empty
                                    if (!content.isEmpty()) {
                                        // Build new SSE JSON
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

                                        // Add other fields
                                        newSseJson.put("created", sseJson.optLong("created", Instant.now().getEpochSecond()));
                                        newSseJson.put("id", sseJson.optString("id", UUID.randomUUID().toString()));
                                        newSseJson.put("model", sseJson.optString("model", requestJson.optString("model")));
                                        newSseJson.put("system_fingerprint", sseJson.optString("system_fingerprint", "fp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)));

                                        // Build new SSE line
                                        String newSseLine = "data: " + newSseJson + "\n\n";

                                        os.write(newSseLine.getBytes(StandardCharsets.UTF_8));
                                        os.flush();
                                    }
                                }
                            }
                        }
                    } catch (JSONException e) {
                        System.err.println("JSON parsing error: " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
            utils.sendError(exchange, "Failed to send response: " + e.getMessage(), 502);
        }
    }

    /**
     * Handle non-stream response
     */
    private void handleNormalResponse(HttpExchange exchange, Map<String, String> headers, JSONObject requestJson) {
        try {
            HttpURLConnection connection = createConnection(headers, requestJson);
            int responseCode = connection.getResponseCode();

            if (responseCode != HttpURLConnection.HTTP_OK) {
                String errorResponse = readStream(connection.getErrorStream());
                utils.sendError(exchange, errorResponse, responseCode);
                return;
            }

            String responseBody = readStream(connection.getInputStream());
            JSONObject responseJson = new JSONObject(responseBody);
            JSONArray choices = responseJson.optJSONArray("choices");
            String assistantContent = "";
            if (choices != null && !choices.isEmpty()) {
                JSONObject firstChoice = choices.getJSONObject(0);
                if (firstChoice.has("message")) {
                    JSONObject message = firstChoice.getJSONObject("message");
                    if (!message.isNull("content")) {
                        assistantContent = message.optString("content", "");
                    }
                }
            }

            // Build OpenAI API style response JSON
            JSONObject openAIResponse = new JSONObject();
            if (responseJson.has("id")) {
                openAIResponse.put("id", responseJson.getString("id"));
            } else {
                openAIResponse.put("id", "chatcmpl-" + UUID.randomUUID());
            }
            if (responseJson.has("object")) {
                openAIResponse.put("object", responseJson.getString("object"));
            } else {
                openAIResponse.put("object", "chat.completion");
            }
            if (responseJson.has("created")) {
                openAIResponse.put("created", responseJson.getLong("created"));
            } else {
                openAIResponse.put("created", Instant.now().getEpochSecond());
            }
            if (responseJson.has("model")) {
                openAIResponse.put("model", responseJson.getString("model"));
            } else {
                openAIResponse.put("model", responseJson.optString("model", "gpt-4o"));
            }
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
            byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, responseBytes.length);
            exchange.getResponseBody().write(responseBytes);
        } catch (Exception e) {
            e.printStackTrace();
            utils.sendError(exchange, "Error occurred while processing response: " + e.getMessage(), 500);
        }
    }

    /**
     * Create and configure HttpURLConnection
     */
    private HttpURLConnection createConnection(Map<String, String> headers, JSONObject jsonBody) throws IOException {
        URL url = new URL(CompletionHandler.COPILOT_CHAT_COMPLETIONS_URL);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(60000); // 60 seconds
        connection.setReadTimeout(60000); // 60 seconds
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
}
