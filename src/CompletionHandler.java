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
import java.util.concurrent.*;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
/**
 * Handler for chat completion requests, adapting the GitHub Copilot API, only handling text generation requests.
 */
public class CompletionHandler implements HttpHandler {
    private static String COPILOT_CHAT_COMPLETIONS_URL = "https://api.individual.githubcopilot.com/chat/completions";

    private final ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

    public static void setCopilotChatCompletionsUrl(String api) {
        COPILOT_CHAT_COMPLETIONS_URL = api;
    }
    public static String getCopilotChatCompletionsUrl() {
        return COPILOT_CHAT_COMPLETIONS_URL;
    }
    /* ---------- JTokkit: 精确 token 计数 ---------- */
    private static final Encoding ENCODING;
    static {
        EncodingRegistry reg = Encodings.newDefaultEncodingRegistry();
        ENCODING = reg.getEncoding(EncodingType.CL100K_BASE);    // GPT-4o / GPT-4 / GPT-3.5 通用
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
                if(authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
                    TokenManager tokenManager = new TokenManager();
                    authorizationHeader="Bearer "+tokenManager.getRandomLongTermToken();
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
                JSONObject streamOpts = requestJson.optJSONObject("stream_options");
                boolean needUsageChunk = streamOpts != null && streamOpts.optBoolean("include_usage", false);

                boolean hasImage = false;
                JSONArray messages = requestJson.optJSONArray("messages");
                if (messages != null) {
                    for (Object object : messages) {
                        JSONObject message = (JSONObject) object;
                        if (message.has("content")) {
                            Object contentObj = message.get("content");
                            if (contentObj instanceof JSONArray) {
                                JSONArray contentArray = (JSONArray) contentObj;
                                for (int j = 0; j < contentArray.length(); j++) {
                                    JSONObject contentItem = contentArray.getJSONObject(j);
                                    if (contentItem.has("type")) {
                                        if (contentItem.getString("type").equals("image_url") && contentItem.has("image_url")) {
                                            hasImage = true;
                                            String type = contentItem.getString("type");
                                                if (type.equals("image_url") && contentItem.has("image_url")) {
                                                // 处理图片内容
                                                JSONObject imageUrlObj = contentItem.getJSONObject("image_url");
                                                String imageURL = imageUrlObj.getString("url");
                                                if (!imageURL.startsWith("data:image/")) {
                                                    // 下载图像转为 base64
                                                    // 使用 OkHttp 下载图像并转换为 Base64
                                                    Request imageRequest = new Request.Builder().url(imageURL).build();
                                                    try (Response imageResponse = utils.getOkHttpClient().newCall(imageRequest).execute()) {
                                                        if (imageResponse.isSuccessful() && imageResponse.body() != null) {
                                                            byte[] imageBytes = imageResponse.body().bytes();
                                                            String base64Image = Base64.getEncoder().encodeToString(imageBytes);
                                                            // 从响应中获取 MIME 类型，若不存在则使用默认值
                                                            String contentType = imageResponse.header("Content-Type");
                                                            if (contentType == null || !contentType.startsWith("image/")) {
                                                                contentType = "image/png"; // 默认类型
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
                JSONObject stream_options = requestJson.optJSONObject("stream_options");
                if(stream_options != null){
                    requestJson.put("stream_options", stream_options);
                }
                // Preparing Headers
                Map<String, String> copilotHeaders = HeadersInfo.getCopilotHeaders(authorizationHeader.substring(7));
                copilotHeaders.put("openai-intent", "conversation-panel");
                copilotHeaders.put("copilot-vision-request", hasImage ? "true" : "false");
                copilotHeaders.put("Authorization", "Bearer " + receivedToken);


                // Call different methods depending on whether it is a streaming return
                if (isStream) {
                    handleStreamResponse(exchange, copilotHeaders, requestJson, needUsageChunk);
                } else {
                    handleNormalResponse(exchange, copilotHeaders, requestJson);
                }

            } catch (Exception e) {
                e.printStackTrace();
                utils.sendError(exchange, "Internal server error: " + e.getMessage(), 500);
            }
        });
    }

    private void handleStreamResponse(HttpExchange exchange,
                                      Map<String,String> headers,
                                      JSONObject requestJson,
                                      boolean needUsageChunk) {
        try (Response resp = utils.executeOkHttpRequest(headers, requestJson,getCopilotChatCompletionsUrl())) {
            if (!resp.isSuccessful()) {
                String err = resp.body()!=null?resp.body().string():"";
                utils.sendError(exchange, err, resp.code()); return;
            }

            /* ---------- SSE 头 ---------- */
            Headers h = exchange.getResponseHeaders();
            h.add("Content-Type","text/event-stream; charset=utf-8");
            h.add("Cache-Control","no-cache");
            h.add("Connection","keep-alive");
            exchange.sendResponseHeaders(200,0);

            int promptTokens = needUsageChunk ? countPromptTokens(requestJson) : 0;
            StringBuilder completionBuf = needUsageChunk ? new StringBuilder() : null;

            try (BufferedSource src = resp.body().source();
                 OutputStream os   = exchange.getResponseBody()) {

                while (!src.exhausted()) {
                    String line = src.readUtf8LineStrict();            // 每行如: "data: {...}"
                    if (!line.startsWith("data: ")) continue;
                    String data = line.substring(6).trim();

                    if ("[DONE]".equals(data)) {
                        /* ----- 尾声: 注入 usage 块（如需） ----- */
                        if (needUsageChunk) {
                            int completionTokens = ENCODING.countTokens(completionBuf.toString());
                            int totalTokens      = promptTokens + completionTokens;

                            JSONObject usage = new JSONObject()
                                    .put("prompt_tokens",     promptTokens)
                                    .put("completion_tokens", completionTokens)
                                    .put("total_tokens",      totalTokens);

                            JSONObject tail = new JSONObject()
                                    .put("choices", new JSONArray())
                                    .put("usage",   usage);

                            String usageLine = "data: " + tail + "\n\n";
                            os.write(usageLine.getBytes(StandardCharsets.UTF_8));
                        }
                        /* ----- 再发官方的 [DONE] ----- */
                        os.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                        os.flush();
                        break;
                    }

                    /* ---------- 解析并重发 delta ---------- */
                    try {
                        JSONObject sse = new JSONObject(data);
                        if (sse.has("choices")) {
                            JSONArray choices = sse.getJSONArray("choices");
                            for (int i=0;i<choices.length();i++) {
                                JSONObject choice = choices.getJSONObject(i);
                                JSONObject delta  = choice.optJSONObject("delta");
                                if (delta!=null && delta.has("content")) {
                                    String content = delta.optString("content","");
                                    if (!content.isEmpty()) {
                                        if (needUsageChunk) completionBuf.append(content);

                                        JSONObject out = buildSSEWrapper(
                                                content, requestJson.optString("model", "gpt-4o"));
                                        String outLine = "data: " + out + "\n\n";
                                        os.write(outLine.getBytes(StandardCharsets.UTF_8));
                                        os.flush();
                                    }
                                }
                            }
                        }
                    } catch (JSONException je) {
                        System.err.println("JSON parse error: "+je.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
            utils.sendError(exchange,"Failed to send response: "+e.getMessage(),502);
        } finally {
            exchange.close();
        }
    }
    /** 统计 prompt tokens */
    private int countPromptTokens(JSONObject req) {
        JSONArray msgs = req.optJSONArray("messages");
        if (msgs==null) return 0;
        StringBuilder sb = new StringBuilder();
        for (int i=0;i<msgs.length();i++) {
            Object c = msgs.getJSONObject(i).opt("content");
            if (c instanceof String) {
                sb.append((String)c).append('\n');
            } else if (c instanceof JSONArray) {
                JSONArray arr = (JSONArray)c;
                for (int j=0;j<arr.length();j++) {
                    JSONObject part = arr.optJSONObject(j);
                    if (part!=null && "text".equals(part.optString("type"))) {
                        sb.append(part.optString("text")).append('\n');
                    }
                }
            }
        }
        return ENCODING.countTokens(sb.toString());
    }
    /** 构造重新包装后的 SSE JSON（只含 content） */
    private JSONObject buildSSEWrapper(String content, String model) {
        JSONObject delta = new JSONObject().put("content", content);
        JSONObject choice = new JSONObject()
                .put("index",0)
                .put("delta",delta);
        JSONArray choices = new JSONArray().put(choice);

        return new JSONObject()
                .put("id","chatcmpl-"+UUID.randomUUID().toString())
                .put("object","chat.completion.chunk")
                .put("created",Instant.now().getEpochSecond())
                .put("model",model)
                .put("choices",choices);
    }
    /**
     * Handle non-stream response
     */
    private void handleNormalResponse(HttpExchange exchange, Map<String, String> headers, JSONObject requestJson) {
        try (Response response = utils.executeOkHttpRequest(headers, requestJson,getCopilotChatCompletionsUrl())) {
            int responseCode = response.code();

            if (!response.isSuccessful()) {
                String errorResponse = response.body() != null ? response.body().string() : "";
                utils.sendError(exchange, errorResponse, responseCode);
                return;
            }

            String responseBody = response.body() != null ? response.body().string() : "{}";

            exchange.getResponseHeaders().add("Content-Type", "application/json");
            System.out.println("responseBody: \n" + responseBody);
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
                .url(getCopilotChatCompletionsUrl())
                .post(body);

        // 设置请求头
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            requestBuilder.addHeader(entry.getKey(), entry.getValue());
        }

        Request request = requestBuilder.build();

        // 发送请求并返回响应
        return utils.getOkHttpClient().newCall(request).execute();
    }
}
