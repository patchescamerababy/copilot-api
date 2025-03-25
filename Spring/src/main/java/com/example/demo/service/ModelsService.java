package com.example.demo.service;

import com.example.demo.util.Utils;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ModelsService {

    // 此处提供静态模型列表（可在静态代码块中添加更多模型）
    public static List<JSONObject> models = new ArrayList<>();

    static {
        try {
            models.add(new JSONObject()
                    .put("preview", false)
                    .put("capabilities", new JSONObject()
                            .put("supports", new JSONObject()
                                    .put("streaming", true)
                                    .put("tool_calls", true))
                            .put("family", "gpt-3.5-turbo")
                            .put("type", "chat")
                            .put("limits", new JSONObject()
                                    .put("max_context_window_tokens", 16384)
                                    .put("max_prompt_tokens", 12288)
                                    .put("max_output_tokens", 4096))
                            .put("object", "model_capabilities")
                            .put("tokenizer", "cl100k_base"))
                    .put("vendor", "Azure OpenAI")
                    .put("model_picker_enabled", false)
                    .put("name", "GPT 3.5 Turbo")
                    .put("id", "gpt-3.5-turbo")
                    .put("version", "gpt-3.5-turbo-0613")
                    .put("object", "model"));
            // 可继续添加其它模型……
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public JSONArray getModels(String authorization) throws Exception {
        if (authorization != null && !authorization.isEmpty()) {
            String token = Utils.getToken(authorization);
            if (token != null && !token.isEmpty()) {
                List<JSONObject> fetchedModels = fetchModels(token);
                return new JSONArray(fetchedModels);
            }
        }
        return new JSONArray(models);
    }

    public List<JSONObject> fetchModels(String token) throws Exception {
        List<JSONObject> fetchedModels = new ArrayList<>();
        OkHttpClient client = Utils.getOkHttpClient();

        Request request = new Request.Builder()
                .url("https://api.individual.githubcopilot.com/models")
                .addHeader("authorization", "Bearer " + token)
                .addHeader("editor-version", com.example.demo.util.HeadersInfo.editor_version)
                .addHeader("copilot_language_server_version", com.example.demo.util.HeadersInfo.copilot_language_server_version)
                .addHeader("openai-intent", "model-access")
                .addHeader("openai-organization", com.example.demo.util.HeadersInfo.openai_organization)
                .addHeader("editor-plugin-version", com.example.demo.util.HeadersInfo.editor_plugin_version)
                .addHeader("x-github-api-version", com.example.demo.util.HeadersInfo.x_github_api_version)
                .addHeader("user-agent", com.example.demo.util.HeadersInfo.user_agent)
                .addHeader("Sec-Fetch-Site", "none")
                .addHeader("Sec-Fetch-Mode", "no-cors")
                .addHeader("Sec-Fetch-Desc", "empty")
                .addHeader("accept", "*/*")
                .addHeader("accept-encoding", "gzip, deflate, br, zstd")
                .addHeader("Connection", "close")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new Exception("Failed to fetch models. HTTP response code: " + response.code());
            }
            String responseBody = response.body().string();
            JSONObject jsonResponse = new JSONObject(responseBody);
            JSONArray jsonArray = jsonResponse.getJSONArray("data");
            for (int i = 0; i < jsonArray.length(); i++) {
                fetchedModels.add(jsonArray.getJSONObject(i));
            }
        }
        return fetchedModels;
    }
}
