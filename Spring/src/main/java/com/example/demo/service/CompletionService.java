package com.example.demo.service;

import com.example.demo.util.HeadersInfo;
import com.example.demo.util.Utils;
import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Map;

@Service
public class CompletionService {

    // 外部 Copilot Chat API 地址
    private String COPILOT_CHAT_COMPLETIONS_URL = "https://api.individual.githubcopilot.com/chat/completions";

    private final OkHttpClient client = Utils.getOkHttpClient();

    public String processRequest(String authorization, String requestBody) throws Exception {
        // 通过 Utils 校验 token（这里只演示调用 getToken 方法）
        String token = Utils.getToken(authorization);
        if (token == null || token.isEmpty()) {
            throw new Exception("Token is invalid");
        }

        JSONObject requestJson = new JSONObject(requestBody);
        String model = requestJson.optString("model", requestJson.getString("model"));
        boolean isStream = requestJson.optBoolean("stream", false);

        boolean isO1 = false;
        if (model.startsWith("o1") || model.startsWith("o3")) {
            isO1 = true;
        } else {
            requestJson.put("stream", isStream);
        }

        boolean hasImage = false;
        JSONArray messages = requestJson.optJSONArray("messages");
        if (messages != null) {
            for (int i = 0; i < messages.length(); i++) {
                JSONObject message = messages.getJSONObject(i);
                if (message.has("content")) {
                    Object contentObj = message.get("content");
                    if (contentObj instanceof JSONArray) {
                        JSONArray contentArray = (JSONArray) contentObj;
                        for (int j = 0; j < contentArray.length(); j++) {
                            JSONObject contentItem = contentArray.getJSONObject(j);
                            if (contentItem.has("type") && "image_url".equals(contentItem.getString("type"))
                                    && contentItem.has("image_url")) {
                                hasImage = true;
                                // 处理图片内容：如果不是 data URI 则下载图片转 base64
                                JSONObject imageUrlObj = contentItem.getJSONObject("image_url");
                                String imageURL = imageUrlObj.getString("url");
                                if (!imageURL.startsWith("data:image/")) {
                                    try {
                                        Request imageRequest = new Request.Builder().url(imageURL).build();
                                        try (Response imageResponse = client.newCall(imageRequest).execute()) {
                                            if (imageResponse.isSuccessful() && imageResponse.body() != null) {
                                                byte[] imageBytes = imageResponse.body().bytes();
                                                String base64Image = java.util.Base64.getEncoder().encodeToString(imageBytes);
                                                String contentType = imageResponse.header("Content-Type");
                                                if (contentType == null || !contentType.startsWith("image/")) {
                                                    contentType = "image/png";
                                                }
                                                String dataUri = "data:" + contentType + ";base64," + base64Image;
                                                imageUrlObj.put("url", dataUri);
                                            }
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

        Map<String, String> copilotHeaders = HeadersInfo.getCopilotHeaders();
        copilotHeaders.put("openai-intent", "conversation-panel");
        copilotHeaders.put("copilot-vision-request", hasImage ? "true" : "false");
        copilotHeaders.put("Authorization", "Bearer " + token);

        System.out.println(requestJson.toString(4));

        // 此处仅处理非流式响应，返回外部 API 的响应原文
        return handleNormalResponse(copilotHeaders, requestJson);
    }

    private String handleNormalResponse(Map<String, String> headers, JSONObject requestJson) throws IOException {
        try (Response response = executeOkHttpRequest(headers, requestJson)) {
            if (!response.isSuccessful()) {
                String errorResponse = response.body() != null ? response.body().string() : "";
                throw new IOException("Error from external API: " + errorResponse);
            }
            String responseBody = response.body() != null ? response.body().string() : "";
            return responseBody;
        }
    }

    private Response executeOkHttpRequest(Map<String, String> headers, JSONObject jsonBody) throws IOException {
        RequestBody body = RequestBody.create(jsonBody.toString(), MediaType.parse("application/json; charset=utf-8"));
        Request.Builder requestBuilder = new Request.Builder().url(COPILOT_CHAT_COMPLETIONS_URL).post(body);
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            requestBuilder.addHeader(entry.getKey(), entry.getValue());
        }
        Request request = requestBuilder.build();
        return client.newCall(request).execute();
    }
}
