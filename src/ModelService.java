import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;


public class ModelService {
    public static List<JSONObject> models = new ArrayList<>();

    static {
        try {
            // Model 1: GPT 3.5 Turbo (first variant)
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

            // Model 2: GPT 3.5 Turbo (second variant)
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
                    .put("id", "gpt-3.5-turbo-0613")
                    .put("version", "gpt-3.5-turbo-0613")
                    .put("object", "model"));

            // Model 3: GPT 4 (first variant)
            models.add(new JSONObject()
                    .put("preview", false)
                    .put("capabilities", new JSONObject()
                            .put("supports", new JSONObject()
                                    .put("streaming", true)
                                    .put("tool_calls", true))
                            .put("family", "gpt-4")
                            .put("type", "chat")
                            .put("limits", new JSONObject()
                                    .put("max_context_window_tokens", 32768)
                                    .put("max_prompt_tokens", 32768)
                                    .put("max_output_tokens", 4096))
                            .put("object", "model_capabilities")
                            .put("tokenizer", "cl100k_base"))
                    .put("vendor", "Azure OpenAI")
                    .put("model_picker_enabled", false)
                    .put("name", "GPT 4")
                    .put("id", "gpt-4")
                    .put("version", "gpt-4-0613")
                    .put("object", "model"));

            // Model 4: GPT 4 (second variant)
            models.add(new JSONObject()
                    .put("preview", false)
                    .put("capabilities", new JSONObject()
                            .put("supports", new JSONObject()
                                    .put("streaming", true)
                                    .put("tool_calls", true))
                            .put("family", "gpt-4")
                            .put("type", "chat")
                            .put("limits", new JSONObject()
                                    .put("max_context_window_tokens", 32768)
                                    .put("max_prompt_tokens", 32768)
                                    .put("max_output_tokens", 4096))
                            .put("object", "model_capabilities")
                            .put("tokenizer", "cl100k_base"))
                    .put("vendor", "Azure OpenAI")
                    .put("model_picker_enabled", false)
                    .put("name", "GPT 4")
                    .put("id", "gpt-4-0613")
                    .put("version", "gpt-4-0613")
                    .put("object", "model"));

            // Model 5: GPT 4o (first variant)
            models.add(new JSONObject()
                    .put("preview", false)
                    .put("capabilities", new JSONObject()
                            .put("supports", new JSONObject()
                                    .put("streaming", true)
                                    .put("parallel_tool_calls", true)
                                    .put("tool_calls", true))
                            .put("family", "gpt-4o")
                            .put("type", "chat")
                            .put("limits", new JSONObject()
                                    .put("vision", new JSONObject()
                                            .put("max_prompt_images", 1)
                                            .put("max_prompt_image_size", 3145728))
                                    .put("max_context_window_tokens", 128000)
                                    .put("max_prompt_tokens", 64000)
                                    .put("max_output_tokens", 4096))
                            .put("object", "model_capabilities")
                            .put("tokenizer", "o200k_base"))
                    .put("vendor", "Azure OpenAI")
                    .put("model_picker_enabled", true)
                    .put("name", "GPT 4o")
                    .put("id", "gpt-4o")
                    .put("version", "gpt-4o-2024-05-13")
                    .put("object", "model"));

            // Model 6: GPT 4o (second variant)
            models.add(new JSONObject()
                    .put("preview", false)
                    .put("capabilities", new JSONObject()
                            .put("supports", new JSONObject()
                                    .put("streaming", true)
                                    .put("parallel_tool_calls", true)
                                    .put("tool_calls", true))
                            .put("family", "gpt-4o")
                            .put("type", "chat")
                            .put("limits", new JSONObject()
                                    .put("vision", new JSONObject()
                                            .put("max_prompt_images", 1)
                                            .put("max_prompt_image_size", 3145728))
                                    .put("max_context_window_tokens", 128000)
                                    .put("max_prompt_tokens", 64000)
                                    .put("max_output_tokens", 4096))
                            .put("object", "model_capabilities")
                            .put("tokenizer", "o200k_base"))
                    .put("vendor", "Azure OpenAI")
                    .put("model_picker_enabled", false)
                    .put("name", "GPT 4o")
                    .put("id", "gpt-4o-2024-05-13")
                    .put("version", "gpt-4o-2024-05-13")
                    .put("object", "model"));

            // Model 7: GPT 4o (third variant - preview id)
            models.add(new JSONObject()
                    .put("preview", false)
                    .put("capabilities", new JSONObject()
                            .put("supports", new JSONObject()
                                    .put("streaming", true)
                                    .put("parallel_tool_calls", true)
                                    .put("tool_calls", true))
                            .put("family", "gpt-4o")
                            .put("type", "chat")
                            .put("limits", new JSONObject()
                                    .put("vision", new JSONObject()
                                            .put("max_prompt_images", 1)
                                            .put("max_prompt_image_size", 3145728))
                                    .put("max_context_window_tokens", 128000)
                                    .put("max_prompt_tokens", 64000)
                                    .put("max_output_tokens", 4096))
                            .put("object", "model_capabilities")
                            .put("tokenizer", "o200k_base"))
                    .put("vendor", "Azure OpenAI")
                    .put("model_picker_enabled", false)
                    .put("name", "GPT 4o")
                    .put("id", "gpt-4-o-preview")
                    .put("version", "gpt-4o-2024-05-13")
                    .put("object", "model"));

            // Model 8: GPT 4o (fourth variant)
            models.add(new JSONObject()
                    .put("preview", false)
                    .put("capabilities", new JSONObject()
                            .put("supports", new JSONObject()
                                    .put("streaming", true)
                                    .put("parallel_tool_calls", true)
                                    .put("tool_calls", true))
                            .put("family", "gpt-4o")
                            .put("type", "chat")
                            .put("limits", new JSONObject()
                                    .put("max_context_window_tokens", 128000)
                                    .put("max_prompt_tokens", 64000)
                                    .put("max_output_tokens", 4096))
                            .put("object", "model_capabilities")
                            .put("tokenizer", "o200k_base"))
                    .put("vendor", "Azure OpenAI")
                    .put("model_picker_enabled", false)
                    .put("name", "GPT 4o")
                    .put("id", "gpt-4o-2024-08-06")
                    .put("version", "gpt-4o-2024-08-06")
                    .put("object", "model"));

            // Model 9: GPT 4o (fifth variant with increased max_output_tokens)
            models.add(new JSONObject()
                    .put("preview", false)
                    .put("capabilities", new JSONObject()
                            .put("supports", new JSONObject()
                                    .put("streaming", true)
                                    .put("parallel_tool_calls", true)
                                    .put("tool_calls", true))
                            .put("family", "gpt-4o")
                            .put("type", "chat")
                            .put("limits", new JSONObject()
                                    .put("max_context_window_tokens", 128000)
                                    .put("max_prompt_tokens", 64000)
                                    .put("max_output_tokens", 16384))
                            .put("object", "model_capabilities")
                            .put("tokenizer", "o200k_base"))
                    .put("vendor", "Azure OpenAI")
                    .put("model_picker_enabled", false)
                    .put("name", "GPT 4o")
                    .put("id", "gpt-4o-2024-11-20")
                    .put("version", "gpt-4o-2024-11-20")
                    .put("object", "model"));

            // Model 10: Embedding V2 Ada
            models.add(new JSONObject()
                    .put("preview", false)
                    .put("capabilities", new JSONObject()
                            .put("supports", new JSONObject())
                            .put("family", "text-embedding-ada-002")
                            .put("type", "embeddings")
                            .put("limits", new JSONObject()
                                    .put("max_inputs", 256))
                            .put("object", "model_capabilities")
                            .put("tokenizer", "cl100k_base"))
                    .put("vendor", "Azure OpenAI")
                    .put("model_picker_enabled", false)
                    .put("name", "Embedding V2 Ada")
                    .put("id", "text-embedding-ada-002")
                    .put("version", "text-embedding-ada-002")
                    .put("object", "model"));

            // Model 11: Embedding V3 small
            models.add(new JSONObject()
                    .put("preview", false)
                    .put("capabilities", new JSONObject()
                            .put("supports", new JSONObject()
                                    .put("dimensions", true))
                            .put("family", "text-embedding-3-small")
                            .put("type", "embeddings")
                            .put("limits", new JSONObject()
                                    .put("max_inputs", 512))
                            .put("object", "model_capabilities")
                            .put("tokenizer", "cl100k_base"))
                    .put("vendor", "Azure OpenAI")
                    .put("model_picker_enabled", false)
                    .put("name", "Embedding V3 small")
                    .put("id", "text-embedding-3-small")
                    .put("version", "text-embedding-3-small")
                    .put("object", "model"));

            // Model 12: Embedding V3 small (Inference)
            models.add(new JSONObject()
                    .put("preview", false)
                    .put("capabilities", new JSONObject()
                            .put("supports", new JSONObject()
                                    .put("dimensions", true))
                            .put("family", "text-embedding-3-small")
                            .put("type", "embeddings")
                            .put("object", "model_capabilities")
                            .put("tokenizer", "cl100k_base"))
                    .put("vendor", "Azure OpenAI")
                    .put("model_picker_enabled", false)
                    .put("name", "Embedding V3 small (Inference)")
                    .put("id", "text-embedding-3-small-inference")
                    .put("version", "text-embedding-3-small")
                    .put("object", "model"));

            // Model 13: GPT 4o Mini (first variant)
            models.add(new JSONObject()
                    .put("preview", false)
                    .put("capabilities", new JSONObject()
                            .put("supports", new JSONObject()
                                    .put("parallel_tool_calls", true)
                                    .put("tool_calls", true))
                            .put("family", "gpt-4o-mini")
                            .put("type", "chat")
                            .put("limits", new JSONObject()
                                    .put("max_context_window_tokens", 128000)
                                    .put("max_prompt_tokens", 12288)
                                    .put("max_output_tokens", 4096))
                            .put("object", "model_capabilities")
                            .put("tokenizer", "o200k_base"))
                    .put("vendor", "Azure OpenAI")
                    .put("model_picker_enabled", false)
                    .put("name", "GPT 4o Mini")
                    .put("id", "gpt-4o-mini")
                    .put("version", "gpt-4o-mini-2024-07-18")
                    .put("object", "model"));

            // Model 14: GPT 4o Mini (second variant duplicate)
            models.add(new JSONObject()
                    .put("preview", false)
                    .put("capabilities", new JSONObject()
                            .put("supports", new JSONObject()
                                    .put("parallel_tool_calls", true)
                                    .put("tool_calls", true))
                            .put("family", "gpt-4o-mini")
                            .put("type", "chat")
                            .put("limits", new JSONObject()
                                    .put("max_context_window_tokens", 128000)
                                    .put("max_prompt_tokens", 12288)
                                    .put("max_output_tokens", 4096))
                            .put("object", "model_capabilities")
                            .put("tokenizer", "o200k_base"))
                    .put("vendor", "Azure OpenAI")
                    .put("model_picker_enabled", false)
                    .put("name", "GPT 4o Mini")
                    .put("id", "gpt-4o-mini")
                    .put("version", "gpt-4o-mini-2024-07-18")
                    .put("object", "model"));

            // Model 15: o3-mini (first variant)
            models.add(new JSONObject()
                    .put("preview", true)
                    .put("capabilities", new JSONObject()
                            .put("supports", new JSONObject()
                                    .put("streaming", true)
                                    .put("tool_calls", true))
                            .put("family", "o3-mini")
                            .put("type", "chat")
                            .put("limits", new JSONObject()
                                    .put("max_context_window_tokens", 200000)
                                    .put("max_prompt_tokens", 20000)
                                    .put("max_output_tokens", 100000))
                            .put("object", "model_capabilities")
                            .put("tokenizer", "o200k_base"))
                    .put("vendor", "Azure OpenAI")
                    .put("model_picker_enabled", true)
                    .put("name", "o3-mini (Preview)")
                    .put("id", "o3-mini")
                    .put("version", "o3-mini-2025-01-31")
                    .put("object", "model"));

            // Model 16: o3-mini (second variant)
            models.add(new JSONObject()
                    .put("preview", true)
                    .put("capabilities", new JSONObject()
                            .put("supports", new JSONObject()
                                    .put("streaming", true)
                                    .put("tool_calls", true))
                            .put("family", "o3-mini")
                            .put("type", "chat")
                            .put("limits", new JSONObject()
                                    .put("max_context_window_tokens", 200000)
                                    .put("max_prompt_tokens", 20000)
                                    .put("max_output_tokens", 100000))
                            .put("object", "model_capabilities")
                            .put("tokenizer", "o200k_base"))
                    .put("vendor", "Azure OpenAI")
                    .put("model_picker_enabled", false)
                    .put("name", "o3-mini (Preview)")
                    .put("id", "o3-mini")
                    .put("version", "o3-mini-2025-01-31")
                    .put("object", "model"));

            // Model 17: o3-mini (paygo variant)
            models.add(new JSONObject()
                    .put("preview", true)
                    .put("capabilities", new JSONObject()
                            .put("supports", new JSONObject()
                                    .put("streaming", true)
                                    .put("tool_calls", true))
                            .put("family", "o3-mini")
                            .put("type", "chat")
                            .put("limits", new JSONObject()
                                    .put("max_context_window_tokens", 200000)
                                    .put("max_prompt_tokens", 20000)
                                    .put("max_output_tokens", 100000))
                            .put("object", "model_capabilities")
                            .put("tokenizer", "o200k_base"))
                    .put("vendor", "Azure OpenAI")
                    .put("model_picker_enabled", false)
                    .put("name", "o3-mini (Preview)")
                    .put("id", "o3-mini-paygo")
                    .put("version", "o3-mini-paygo")
                    .put("object", "model"));

            // Model 18: Claude 3.5 Sonnet (Preview)
            models.add(new JSONObject()
                    .put("preview", true)
                    .put("capabilities", new JSONObject()
                            .put("supports", new JSONObject()
                                    .put("streaming", true)
                                    .put("parallel_tool_calls", true)
                                    .put("tool_calls", true))
                            .put("family", "claude-3.5-sonnet")
                            .put("type", "chat")
                            .put("limits", new JSONObject()
                                    .put("vision", new JSONObject()
                                            .put("max_prompt_images", 1)
                                            .put("max_prompt_image_size", 3145728))
                                    .put("max_context_window_tokens", 90000)
                                    .put("max_prompt_tokens", 90000)
                                    .put("max_output_tokens", 4096))
                            .put("object", "model_capabilities")
                            .put("tokenizer", "o200k_base"))
                    .put("vendor", "Anthropic")
                    .put("model_picker_enabled", true)
                    .put("name", "Claude 3.5 Sonnet (Preview)")
                    .put("id", "claude-3.5-sonnet")
                    .put("version", "claude-3.5-sonnet")
                    .put("object", "model")
                    .put("policy", new JSONObject()
                            .put("terms", "Enable access to the latest Claude 3.5 Sonnet model from Anthropic. [Learn more about how GitHub Copilot serves Claude 3.5 Sonnet](https://docs.github.com/copilot/using-github-copilot/using-claude-sonnet-in-github-copilot).")
                            .put("state", "enabled")));

            // Model 19: Gemini 2.0 Flash (Preview)
            models.add(new JSONObject()
                    .put("preview", true)
                    .put("capabilities", new JSONObject()
                            .put("supports", new JSONObject()
                                    .put("streaming", true)
                                    .put("parallel_tool_calls", true)
                                    .put("tool_calls", true))
                            .put("family", "gemini-2.0-flash")
                            .put("type", "chat")
                            .put("limits", new JSONObject()
                                    .put("vision", new JSONObject()
                                            .put("max_prompt_images", 1)
                                            .put("max_prompt_image_size", 3145728))
                                    .put("max_context_window_tokens", 1000000)
                                    .put("max_prompt_tokens", 128000)
                                    .put("max_output_tokens", 8192))
                            .put("object", "model_capabilities")
                            .put("tokenizer", "o200k_base"))
                    .put("vendor", "Google")
                    .put("model_picker_enabled", true)
                    .put("name", "Gemini 2.0 Flash (Preview)")
                    .put("id", "gemini-2.0-flash-001")
                    .put("version", "gemini-2.0-flash-001")
                    .put("object", "model")
                    .put("policy", new JSONObject()
                            .put("terms", "Enable access to the latest Gemini models from Google. [Learn more about how GitHub Copilot serves Gemini 2.0 Flash](https://docs.github.com/en/copilot/using-github-copilot/ai-models/using-gemini-flash-in-github-copilot).")
                            .put("state", "enabled")));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Get a list of models from the GitHub Copilot API
     *
     * @return a list of models
     * @throws Exception if any errors occur
     */
    public static List<JSONObject> fetchModels(String token) throws Exception {
        List<JSONObject> fetchedModels = new ArrayList<>();

        OkHttpClient client = utils.getOkHttpClient();

        Request request = new Request.Builder()
                .url("https://api.individual.githubcopilot.com/models")
                .addHeader("authorization", "Bearer " + token)
                .addHeader("editor-version", HeadersInfo.editor_version)
                .addHeader("copilot_language_server_version", HeadersInfo.copilot_language_server_version)
                .addHeader("openai-intent", "model-access")
                .addHeader("openai-organization", HeadersInfo.openai_organization)
                .addHeader("editor-plugin-version", HeadersInfo.editor_plugin_version)
                .addHeader("x-github-api-version", HeadersInfo.x_github_api_version)
                .addHeader("user-agent", HeadersInfo.user_agent)
                .addHeader("Sec-Fetch-Site", "none")
                .addHeader("Sec-Fetch-Mode", "no-cors")
                .addHeader("Sec-Fetch-Desc", "empty")
                .addHeader("accept", "*/*")
                .addHeader("accept-encoding", "gzip, deflate, br zstd")
                .addHeader("Connection", "close")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new RuntimeException("Failed to fetch models. HTTP response code: " + response.code());
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
