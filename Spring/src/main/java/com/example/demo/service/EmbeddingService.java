package com.example.demo.service;

import com.example.demo.util.HeadersInfo;
import com.example.demo.util.Utils;
import okhttp3.*;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Map;

@Service
public class EmbeddingService {

    public static final String COPILOT_CHAT_EMBEDDINGS_URL = "https://api.individual.githubcopilot.com/embeddings";

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(java.time.Duration.ofSeconds(120))
            .readTimeout(java.time.Duration.ofSeconds(120))
            .build();

    public String processEmbeddingRequest(String authorization, String requestBody) throws Exception {
        String token = Utils.getToken(authorization);
        if (token == null || token.isEmpty()) {
            throw new Exception("Token is invalid");
        }
        JSONObject requestJson = new JSONObject(requestBody);
        Map<String, String> headers = HeadersInfo.getCopilotHeaders();
        headers.put("Authorization", "Bearer " + token);

        try (Response response = executeOkHttpRequest(headers, requestJson)) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("Failed to get embeddings: " + responseBody);
            }
            return responseBody;
        }
    }

    private Response executeOkHttpRequest(Map<String, String> headers, JSONObject jsonBody) throws IOException {
        RequestBody body = RequestBody.create(jsonBody.toString(), MediaType.parse("application/json; charset=utf-8"));
        Request.Builder requestBuilder = new Request.Builder().url(COPILOT_CHAT_EMBEDDINGS_URL).post(body);
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            requestBuilder.addHeader(entry.getKey(), entry.getValue());
        }
        Request request = requestBuilder.build();
        return client.newCall(request).execute();
    }
}
