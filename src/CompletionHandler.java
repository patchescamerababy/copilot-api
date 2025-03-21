import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import okhttp3.*;
import okio.BufferedSource;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import java.util.stream.Collectors;

/**
 * Handler for chat completion requests, adapting the GitHub Copilot API, only handling text generation requests.
 */
public class CompletionHandler implements HttpHandler {
    private static String COPILOT_CHAT_COMPLETIONS_URL = "https://api.individual.githubcopilot.com/chat/completions";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    
    private static final OkHttpClient okHttpClient = utils.getOkHttpClient();

    public static void setCopilotChatCompletionsUrl(String api) {
        COPILOT_CHAT_COMPLETIONS_URL = api;
    }

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
                String model = requestJson.getString("model");
                boolean isStream = requestJson.optBoolean("stream", false);

                // Build a new request JSON, adapting to the Copilot API
                boolean isO1 = false;
                System.out.println(model);
                if (model.startsWith("o1")) {
                    System.out.println("stream: false");
                    isO1 = true;
                    requestJson.put("stream", false);
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
                                StringBuilder msgContentBuilder = new StringBuilder();
                                for (int j = 0; j < contentArray.length(); j++) {
                                    JSONObject contentItem = contentArray.getJSONObject(j);
                                    if (contentItem.has("type")) {
                                        if (contentItem.getString("type").equals("image_url") && contentItem.has("image_url")) {
                                            hasImage = true;
                                            String type = contentItem.getString("type");
                                            if (type.equals("text") && contentItem.has("text")) {
                                                // 处理文本内容
                                                String text = contentItem.getString("text");
                                                msgContentBuilder.append(text);
                                                if (j < contentArray.length() - 1) {
                                                    msgContentBuilder.append(" ");
                                                }
                                            } else if (type.equals("image_url") && contentItem.has("image_url")) {
                                                // 处理图片内容
                                                JSONObject imageUrlObj = contentItem.getJSONObject("image_url");
                                                String imageURL = imageUrlObj.getString("url");
                                                if (!imageURL.startsWith("data:image/")) {
                                                    // 下载图像转为 base64
                                                    Request imageRequest = new Request.Builder().url(imageURL).build();
                                                    try (Response imageResponse = okHttpClient.newCall(imageRequest).execute()) {
                                                        if (imageResponse.isSuccessful() && imageResponse.body() != null) {
                                                            byte[] imageBytes = imageResponse.body().bytes();
                                                            String base64Image = Base64.getEncoder().encodeToString(imageBytes);
                                                            // 从响应中获取 MIME 类型，若不存在则使用默认值
                                                            String contentType = imageResponse.header("Content-Type");
                                                            if (contentType == null || !contentType.startsWith("image/")) {
                                                                contentType = "image/jpeg"; 
                                                            }
                                                            String dataUri = "data:" + contentType + ";base64," + base64Image;
                                                            imageUrlObj.put("url", dataUri);
                                                        } else {
                                                            System.err.println("Failed to download image. Response code: " + imageResponse.code());
                                                        }
                                                    } catch (Exception e) {
                                                        e.printStackTrace();
                                                    }

                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Preparing Headers
                Map<String, String> copilotHeaders = HeadersInfo.getCopilotHeaders();
                copilotHeaders.put("openai-intent", "conversation-panel");
                copilotHeaders.put("copilot-vision-request", hasImage ? "true" : "false");
                copilotHeaders.put("Authorization", "Bearer " + receivedToken); // Update Token
//                System.out.println(requestJson.toString(4));

                // Call different methods depending on whether it is a streaming return
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

    /**
     * Adapting O1 streaming response processing
     */
    private void handleO1StreamResponse(HttpExchange exchange, Map<String, String> headers, JSONObject requestJson) {
        try (Response response = executeOkHttpRequest(headers, requestJson)) {
            int responseCode = response.code();

            if (!response.isSuccessful()) {
                String errorResponse = response.body() != null ? response.body().string() : "";
                utils.sendError(exchange, errorResponse, responseCode);
                return;
            }

            String responseBody = response.body() != null ? response.body().string() : "";

            JSONObject apiResponse = new JSONObject(responseBody);
            String assistantContent = "";
            if (apiResponse.has("choices") && !apiResponse.isNull("choices")) {
                JSONArray choices = apiResponse.getJSONArray("choices");
                for (int i = 0; i < choices.length(); i++) {
                    JSONObject choice = choices.getJSONObject(i);
                    JSONObject message = choice.optJSONObject("message",choice.optJSONObject("Message"));
                    if ("assistant".equalsIgnoreCase(message.optString("role", ""))) {
                        assistantContent = message.optString("content", "").trim();
                        break;
                    }
                }
            }
            
            Headers responseHeaders = exchange.getResponseHeaders();
            responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8");
            responseHeaders.add("Cache-Control", "no-cache");
            responseHeaders.add("Connection", "keep-alive");
            exchange.sendResponseHeaders(200, 0);

            try (OutputStream os = exchange.getResponseBody()) {
                JSONObject sseJson = new JSONObject();
                JSONArray choicesArray = new JSONArray();

                JSONObject choiceObj = new JSONObject();
                choiceObj.put("index", 0);
                JSONObject contentFilterResults = new JSONObject();
                contentFilterResults.put("error", new JSONObject().put("code", "").put("message", ""));
                contentFilterResults.put("hate", new JSONObject().put("filtered", false).put("severity", "safe"));
                contentFilterResults.put("self_harm", new JSONObject().put("filtered", false).put("severity", "safe"));
                contentFilterResults.put("sexual", new JSONObject().put("filtered", false).put("severity", "safe"));
                contentFilterResults.put("violence", new JSONObject().put("filtered", false).put("severity", "safe"));
                choiceObj.put("content_filter_results", contentFilterResults);

                JSONObject delta = new JSONObject();
                delta.put("content", assistantContent);
                choiceObj.put("delta", delta);

                choicesArray.put(choiceObj);
                sseJson.put("choices", choicesArray);

                sseJson.put("created", apiResponse.optLong("created", Instant.now().getEpochSecond()));
                sseJson.put("id", apiResponse.optString("id", ""));
                sseJson.put("model", apiResponse.optString("model", requestJson.optString("model")));
                sseJson.put("system_fingerprint",apiResponse.optString("apiResponse","fp_"+UUID.randomUUID().toString().replace("-", "").substring(0, 12)));
                String sseLine = "data: " + sseJson.toString() + "\n\n";
                os.write(sseLine.getBytes(StandardCharsets.UTF_8));
                os.flush();

                String doneLine = "data: [DONE]\n\n";
                os.write(doneLine.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

        } catch (Exception e) {
            e.printStackTrace();
            utils.sendError(exchange, "Error occurred while processing response: " + e.getMessage(), 500);
        }
    }



    /**
     * Handle stream response using OkHttp
     */
    private void handleStreamResponse(HttpExchange exchange, Map<String, String> headers, JSONObject requestJson) throws IOException {
        try (Response response = executeOkHttpRequest(headers, requestJson)) {
            int responseCode = response.code();

            if (!response.isSuccessful()) {
                String errorResponse = response.body() != null ? response.body().string() : "";
                utils.sendError(exchange, errorResponse, responseCode);
                return;
            }

            exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().add("Cache-Control", "no-cache");
            exchange.getResponseHeaders().add("Connection", "keep-alive");
            exchange.sendResponseHeaders(200, 0);

            // Reading SSE Streams
            if (response.body() != null) {
                try (BufferedSource source = response.body().source();
                     OutputStream os = exchange.getResponseBody()) {
                    while (!source.exhausted()) {
                        String line = source.readUtf8LineStrict();
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
                                            // 只处理非空内容
                                            if (!content.isEmpty()) {
                                                // 构造新的 SSE JSON
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

                                                newSseJson.put("created", sseJson.optLong("created",
                                                        Instant.now().getEpochSecond()));
                                                newSseJson.put("id", sseJson.optString("id",
                                                        UUID.randomUUID().toString()));
                                                newSseJson.put("model", sseJson.optString("model",
                                                        requestJson.optString("model")));
                                                newSseJson.put("system_fingerprint", sseJson.optString("system_fingerprint",
                                                        "fp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)));

                                                // 构造 SSE line
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
        try (Response response = executeOkHttpRequest(headers, requestJson)) {
            int responseCode = response.code();

            if (!response.isSuccessful()) {
                String errorResponse = response.body() != null ? response.body().string() : "";
                utils.sendError(exchange, errorResponse, responseCode);
                return;
            }

            String responseBody = response.body() != null ? response.body().string() : "";
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
                .url(COPILOT_CHAT_COMPLETIONS_URL)
                .post(body);

        // 设置请求头
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            requestBuilder.addHeader(entry.getKey(), entry.getValue());
        }

        Request request = requestBuilder.build();

        // 发送请求并返回响应
        return okHttpClient.newCall(request).execute();
    }
}
