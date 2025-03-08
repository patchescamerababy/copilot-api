const tokenStore = new Map();
const openai_organization = "github-copilot";
const editor_version = "vscode/1.98.0-insider";
const editor_plugin_version = "copilot/1.270.0";
const copilot_language_server_version = "1.270.0";
const x_github_api_version = "2025-01-21";
const user_agent = "GitHubCopilotChat/0.23.2";


async function fetchNewToken(longTermToken) {
    const url = "https://api.github.com/copilot_internal/v2/token";
    const init = {
        method: "GET",
        headers: {
            "Authorization": "token " + longTermToken,
            "Editor-Plugin-Version": "copilot-chat/0.23.2",
            "Editor-Version": "vscode/1.98.0-insider",
            "User-Agent": "GitHubCopilotChat/0.23.2",
            "x-github-api-version": "2024-12-15",
            "Sec-Fetch-Site": "none",
            "Sec-Fetch-Mode": "no-cors",
            "Sec-Fetch-Dest": "empty"
        }
    };
    const resp = await fetch(url, init);
    if (resp.ok) {
        const json = await resp.json();
        if (json.token) {
            console.log("New Token:\n", json.token);
            return json.token
        } else {
            console.error('"token" 字段未找到');
        }
    } else {
        const errText = await resp.text();
        console.error("Request failed, status:", resp.status, errText);
    }
    return null;
}

// 从 token 字符串中提取过期时间（格式： "tid=xxx;exp=1731950502"）
function extractTimestamp(tokenStr) {
    const parts = tokenStr.split(";");
    for (const part of parts) {
        if (part.startsWith("exp=")) {
            return parseInt(part.substring(4));
        }
    }
    return 0;
}

// 判断 token 是否过期
function isTokenExpired(tokenStr) {
    const exp = extractTimestamp(tokenStr);
    const now = Math.floor(Date.now() / 1000);
    return exp < now;
}

// 获取短期 token。如果已有且未过期，则直接返回；否则获取新 token 并更新
async function getValidTempToken(longTermToken) {
    const record = tokenStore.get(longTermToken);
    if (record && record.tempToken && !isTokenExpired(record.tempToken)) {
        return record.tempToken;
    }
    const newToken = await fetchNewToken(longTermToken);
    if (!newToken) {
        throw new Error("无法生成新的短期 token");
    }
    const newExpiry = extractTimestamp(newToken);
    tokenStore.set(longTermToken, { tempToken: newToken, expiry: newExpiry });
    return newToken;
}

// 从请求中提取 Bearer token。如果未提供，则尝试从随机选取一个 token
async function getTokenFromRequest(request) {
    let authHeader = request.headers.get("Authorization");
    let longTermToken;
    if (!authHeader || !authHeader.startsWith("Bearer ")) {
        // 如果未提供，则尝试使用已有的 token
        if (tokenStore.size > 0) {
            const keys = Array.from(tokenStore.keys());
            longTermToken = keys[Math.floor(Math.random() * keys.length)];
            console.log("使用随机 longTermToken:", longTermToken);
        } else {
            return null;
        }
    } else {
        longTermToken = authHeader.substring("Bearer ".length).trim();
        if (!longTermToken) return null;
        // 检查 token 前缀（必须以 "ghu" 或 "gho" 开头）
        if (!(longTermToken.startsWith("ghu") || longTermToken.startsWith("gho"))) {
            return null;
        }
    }
    return await getValidTempToken(longTermToken);
}

// 生成 Copilot API 请求所需的 headers
function getModelHeaders(token) {
    return {
        "Content-Type": "application/json",
        "Connection": "keep-alive",
        "openai-intent": "model-access",
        "Editor-Plugin-Version": editor_plugin_version,
        "Editor-Version": editor_version,
        "Openai-Organization": openai_organization,
        "User-Agent": user_agent,
        "VScode-MachineId": generateRandomHex(64),
        "VScode-SessionId": generateUUID(),
        "accept": "*/*",
        "Sec-Fetch-Site": "none",
        "Sec-Fetch-Mode": "no-cors",
        "Sec-Fetch-Dest": "empty",
        "accept-encoding": "gzip, deflate, br, zstd",
        "X-GitHub-Api-Version": x_github_api_version,
        "X-Request-Id": randomRequestId(),
        "copilot-integration-id": "vscode-chat",
        "Copilot-Vision-Request": "true",
        "Authorization": "Bearer " + token
    };
}

function getEmbeddingsHeaders(token) {
    return {
        "Content-Type": "application/json",
        "Connection": "keep-alive",
        "Editor-Plugin-Version": editor_plugin_version,
        "Editor-Version": editor_version,
        "Openai-Organization": openai_organization,
        "User-Agent": user_agent,
        "VScode-MachineId": generateRandomHex(64),
        "VScode-SessionId": generateUUID(),
        "accept": "*/*",
        "Sec-Fetch-Site": "none",
        "Sec-Fetch-Mode": "no-cors",
        "Sec-Fetch-Dest": "empty",
        "accept-encoding": "gzip, deflate, br, zstd",
        "X-GitHub-Api-Version": x_github_api_version,
        "X-Request-Id": randomRequestId(),
        "Copilot-Vision-Request": "true",
        "Authorization": "Bearer " + token
    };
}

function getCompletionsHeaders(token) {
    return {
        "Content-Type": "application/json",
        "Connection": "keep-alive",
        "copilot-vision-request": "true",
        "openai-intent": "conversation-panel",
        "Editor-Plugin-Version": editor_plugin_version,
        "Editor-Version": editor_version,
        "Openai-Organization": openai_organization,
        "User-Agent": user_agent,
        "VScode-MachineId": generateRandomHex(64),
        "VScode-SessionId": generateUUID(),
        "accept": "*/*",
        "Sec-Fetch-Site": "none",
        "Sec-Fetch-Mode": "no-cors",
        "Sec-Fetch-Dest": "empty",
        "accept-encoding": "gzip, deflate, br, zstd",
        "X-GitHub-Api-Version": x_github_api_version,
        "X-Request-Id": randomRequestId(),
        "copilot-integration-id": "vscode-chat",
        "Copilot-Vision-Request": "true",
        "Authorization": "Bearer " + token
    };
}

function generateUUID() {
    const pattern = [1e7, -1e3, -4e3, -8e3, -1e11].map(String).join('');
    // 根据模板生成 UUID（采用 UUID v4 算法）
    const uuidPart = pattern.replace(/[018]/g, c =>
        (parseInt(c) ^ crypto.getRandomValues(new Uint8Array(1))[0] & 15 >> (parseInt(c) / 4)).toString(16)
    );
    const randomPart = String(Math.floor(Math.random() * 1e13));
    return uuidPart + randomPart;
}

function generateRandomHex(length) {
    let result = "";
    const hexChars = "0123456789abcdef";
    for (let i = 0; i < length; i++) {
        result += hexChars.charAt(Math.floor(Math.random() * hexChars.length));
    }
    return result;
}

// 生成随机 Request ID，格式为 xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
function randomRequestId() {
    const chars = "abcdefghijklmnopqrstuvwxyz0123456789";
    let s = "";
    for (let i = 0; i < 32; i++) {
        s += chars.charAt(Math.floor(Math.random() * chars.length));
    }
    return `${s.substring(0,8)}-${s.substring(8,12)}-${s.substring(12,16)}-${s.substring(16,20)}-${s.substring(20,32)}`;
}


// 定义静态模型列表
const defaultModels = [
    {
        "preview": false,
        "capabilities": {
            "supports": {
                "streaming": true,
                "tool_calls": true
            },
            "family": "gpt-3.5-turbo",
            "type": "chat",
            "limits": {
                "max_context_window_tokens": 16384,
                "max_prompt_tokens": 12288,
                "max_output_tokens": 4096
            },
            "object": "model_capabilities",
            "tokenizer": "cl100k_base"
        },
        "vendor": "Azure OpenAI",
        "model_picker_enabled": false,
        "name": "GPT 3.5 Turbo",
        "id": "gpt-3.5-turbo",
        "version": "gpt-3.5-turbo-0613",
        "object": "model"
    },
    {
        "preview": false,
        "capabilities": {
            "supports": {
                "streaming": true,
                "tool_calls": true
            },
            "family": "gpt-3.5-turbo",
            "type": "chat",
            "limits": {
                "max_context_window_tokens": 16384,
                "max_prompt_tokens": 12288,
                "max_output_tokens": 4096
            },
            "object": "model_capabilities",
            "tokenizer": "cl100k_base"
        },
        "vendor": "Azure OpenAI",
        "model_picker_enabled": false,
        "name": "GPT 3.5 Turbo",
        "id": "gpt-3.5-turbo-0613",
        "version": "gpt-3.5-turbo-0613",
        "object": "model"
    },
    {
        "preview": false,
        "capabilities": {
            "supports": {
                "streaming": true,
                "tool_calls": true
            },
            "family": "gpt-4",
            "type": "chat",
            "limits": {
                "max_context_window_tokens": 32768,
                "max_prompt_tokens": 32768,
                "max_output_tokens": 4096
            },
            "object": "model_capabilities",
            "tokenizer": "cl100k_base"
        },
        "vendor": "Azure OpenAI",
        "model_picker_enabled": false,
        "name": "GPT 4",
        "id": "gpt-4",
        "version": "gpt-4-0613",
        "object": "model"
    },
    {
        "preview": false,
        "capabilities": {
            "supports": {
                "streaming": true,
                "tool_calls": true
            },
            "family": "gpt-4",
            "type": "chat",
            "limits": {
                "max_context_window_tokens": 32768,
                "max_prompt_tokens": 32768,
                "max_output_tokens": 4096
            },
            "object": "model_capabilities",
            "tokenizer": "cl100k_base"
        },
        "vendor": "Azure OpenAI",
        "model_picker_enabled": false,
        "name": "GPT 4",
        "id": "gpt-4-0613",
        "version": "gpt-4-0613",
        "object": "model"
    },
    {
        "preview": false,
        "capabilities": {
            "supports": {
                "streaming": true,
                "parallel_tool_calls": true,
                "tool_calls": true
            },
            "family": "gpt-4o",
            "type": "chat",
            "limits": {
                "vision": {
                    "max_prompt_images": 1,
                    "max_prompt_image_size": 3145728
                },
                "max_context_window_tokens": 128000,
                "max_prompt_tokens": 64000,
                "max_output_tokens": 4096
            },
            "object": "model_capabilities",
            "tokenizer": "o200k_base"
        },
        "vendor": "Azure OpenAI",
        "model_picker_enabled": true,
        "name": "GPT 4o",
        "id": "gpt-4o",
        "version": "gpt-4o-2024-05-13",
        "object": "model"
    },
    {
        "preview": false,
        "capabilities": {
            "supports": {
                "streaming": true,
                "parallel_tool_calls": true,
                "tool_calls": true
            },
            "family": "gpt-4o",
            "type": "chat",
            "limits": {
                "vision": {
                    "max_prompt_images": 1,
                    "max_prompt_image_size": 3145728
                },
                "max_context_window_tokens": 128000,
                "max_prompt_tokens": 64000,
                "max_output_tokens": 4096
            },
            "object": "model_capabilities",
            "tokenizer": "o200k_base"
        },
        "vendor": "Azure OpenAI",
        "model_picker_enabled": false,
        "name": "GPT 4o",
        "id": "gpt-4o-2024-05-13",
        "version": "gpt-4o-2024-05-13",
        "object": "model"
    },
    {
        "preview": false,
        "capabilities": {
            "supports": {
                "streaming": true,
                "parallel_tool_calls": true,
                "tool_calls": true
            },
            "family": "gpt-4o",
            "type": "chat",
            "limits": {
                "vision": {
                    "max_prompt_images": 1,
                    "max_prompt_image_size": 3145728
                },
                "max_context_window_tokens": 128000,
                "max_prompt_tokens": 64000,
                "max_output_tokens": 4096
            },
            "object": "model_capabilities",
            "tokenizer": "o200k_base"
        },
        "vendor": "Azure OpenAI",
        "model_picker_enabled": false,
        "name": "GPT 4o",
        "id": "gpt-4-o-preview",
        "version": "gpt-4o-2024-05-13",
        "object": "model"
    },
    {
        "preview": false,
        "capabilities": {
            "supports": {
                "streaming": true,
                "parallel_tool_calls": true,
                "tool_calls": true
            },
            "family": "gpt-4o",
            "type": "chat",
            "limits": {
                "max_context_window_tokens": 128000,
                "max_prompt_tokens": 64000,
                "max_output_tokens": 4096
            },
            "object": "model_capabilities",
            "tokenizer": "o200k_base"
        },
        "vendor": "Azure OpenAI",
        "model_picker_enabled": false,
        "name": "GPT 4o",
        "id": "gpt-4o-2024-08-06",
        "version": "gpt-4o-2024-08-06",
        "object": "model"
    },
    {
        "preview": false,
        "capabilities": {
            "supports": {
                "streaming": true,
                "parallel_tool_calls": true,
                "tool_calls": true
            },
            "family": "gpt-4o",
            "type": "chat",
            "limits": {
                "max_context_window_tokens": 128000,
                "max_prompt_tokens": 64000,
                "max_output_tokens": 16384
            },
            "object": "model_capabilities",
            "tokenizer": "o200k_base"
        },
        "vendor": "Azure OpenAI",
        "model_picker_enabled": false,
        "name": "GPT 4o",
        "id": "gpt-4o-2024-11-20",
        "version": "gpt-4o-2024-11-20",
        "object": "model"
    },
    {
        "preview": false,
        "capabilities": {
            "supports": {},
            "family": "text-embedding-ada-002",
            "type": "embeddings",
            "limits": {
                "max_inputs": 256
            },
            "object": "model_capabilities",
            "tokenizer": "cl100k_base"
        },
        "vendor": "Azure OpenAI",
        "model_picker_enabled": false,
        "name": "Embedding V2 Ada",
        "id": "text-embedding-ada-002",
        "version": "text-embedding-ada-002",
        "object": "model"
    },
    {
        "preview": false,
        "capabilities": {
            "supports": {
                "dimensions": true
            },
            "family": "text-embedding-3-small",
            "type": "embeddings",
            "limits": {
                "max_inputs": 512
            },
            "object": "model_capabilities",
            "tokenizer": "cl100k_base"
        },
        "vendor": "Azure OpenAI",
        "model_picker_enabled": false,
        "name": "Embedding V3 small",
        "id": "text-embedding-3-small",
        "version": "text-embedding-3-small",
        "object": "model"
    },
    {
        "preview": false,
        "capabilities": {
            "supports": {
                "dimensions": true
            },
            "family": "text-embedding-3-small",
            "type": "embeddings",
            "object": "model_capabilities",
            "tokenizer": "cl100k_base"
        },
        "vendor": "Azure OpenAI",
        "model_picker_enabled": false,
        "name": "Embedding V3 small (Inference)",
        "id": "text-embedding-3-small-inference",
        "version": "text-embedding-3-small",
        "object": "model"
    },
    {
        "preview": false,
        "capabilities": {
            "supports": {
                "parallel_tool_calls": true,
                "tool_calls": true
            },
            "family": "gpt-4o-mini",
            "type": "chat",
            "limits": {
                "max_context_window_tokens": 128000,
                "max_prompt_tokens": 12288,
                "max_output_tokens": 4096
            },
            "object": "model_capabilities",
            "tokenizer": "o200k_base"
        },
        "vendor": "Azure OpenAI",
        "model_picker_enabled": false,
        "name": "GPT 4o Mini",
        "id": "gpt-4o-mini",
        "version": "gpt-4o-mini-2024-07-18",
        "object": "model"
    },
    {
        "preview": false,
        "capabilities": {
            "supports": {
                "parallel_tool_calls": true,
                "tool_calls": true
            },
            "family": "gpt-4o-mini",
            "type": "chat",
            "limits": {
                "max_context_window_tokens": 128000,
                "max_prompt_tokens": 12288,
                "max_output_tokens": 4096
            },
            "object": "model_capabilities",
            "tokenizer": "o200k_base"
        },
        "vendor": "Azure OpenAI",
        "model_picker_enabled": false,
        "name": "GPT 4o Mini",
        "id": "gpt-4o-mini",
        "version": "gpt-4o-mini-2024-07-18",
        "object": "model"
    },
    {
        "preview": true,
        "capabilities": {
            "supports": {
                "streaming": true,
                "tool_calls": true
            },
            "family": "o3-mini",
            "type": "chat",
            "limits": {
                "max_context_window_tokens": 200000,
                "max_prompt_tokens": 20000,
                "max_output_tokens": 100000
            },
            "object": "model_capabilities",
            "tokenizer": "o200k_base"
        },
        "vendor": "Azure OpenAI",
        "model_picker_enabled": true,
        "name": "o3-mini (Preview)",
        "id": "o3-mini",
        "version": "o3-mini-2025-01-31",
        "object": "model"
    },
    {
        "preview": true,
        "capabilities": {
            "supports": {
                "streaming": true,
                "tool_calls": true
            },
            "family": "o3-mini",
            "type": "chat",
            "limits": {
                "max_context_window_tokens": 200000,
                "max_prompt_tokens": 20000,
                "max_output_tokens": 100000
            },
            "object": "model_capabilities",
            "tokenizer": "o200k_base"
        },
        "vendor": "Azure OpenAI",
        "model_picker_enabled": false,
        "name": "o3-mini (Preview)",
        "id": "o3-mini",
        "version": "o3-mini-2025-01-31",
        "object": "model"
    },
    {
        "preview": true,
        "capabilities": {
            "supports": {
                "streaming": true,
                "tool_calls": true
            },
            "family": "o3-mini",
            "type": "chat",
            "limits": {
                "max_context_window_tokens": 200000,
                "max_prompt_tokens": 20000,
                "max_output_tokens": 100000
            },
            "object": "model_capabilities",
            "tokenizer": "o200k_base"
        },
        "vendor": "Azure OpenAI",
        "model_picker_enabled": false,
        "name": "o3-mini (Preview)",
        "id": "o3-mini-paygo",
        "version": "o3-mini-paygo",
        "object": "model"
    },
    {
        "preview": true,
        "capabilities": {
            "supports": {
                "streaming": true,
                "parallel_tool_calls": true,
                "tool_calls": true
            },
            "family": "claude-3.5-sonnet",
            "type": "chat",
            "limits": {
                "vision": {
                    "max_prompt_images": 1,
                    "max_prompt_image_size": 3145728
                },
                "max_context_window_tokens": 90000,
                "max_prompt_tokens": 90000,
                "max_output_tokens": 4096
            },
            "object": "model_capabilities",
            "tokenizer": "o200k_base"
        },
        "vendor": "Anthropic",
        "model_picker_enabled": true,
        "name": "Claude 3.5 Sonnet (Preview)",
        "id": "claude-3.5-sonnet",
        "version": "claude-3.5-sonnet",
        "object": "model",
        "policy": {
            "terms": "Enable access to the latest Claude 3.5 Sonnet model from Anthropic. [Learn more about how GitHub Copilot serves Claude 3.5 Sonnet](https://docs.github.com/copilot/using-github-copilot/using-claude-sonnet-in-github-copilot).",
            "state": "enabled"
        }
    },
    {
        "preview": true,
        "capabilities": {
            "supports": {
                "streaming": true,
                "parallel_tool_calls": true,
                "tool_calls": true
            },
            "family": "gemini-2.0-flash",
            "type": "chat",
            "limits": {
                "vision": {
                    "max_prompt_images": 1,
                    "max_prompt_image_size": 3145728
                },
                "max_context_window_tokens": 1000000,
                "max_prompt_tokens": 128000,
                "max_output_tokens": 8192
            },
            "object": "model_capabilities",
            "tokenizer": "o200k_base"
        },
        "vendor": "Google",
        "model_picker_enabled": true,
        "name": "Gemini 2.0 Flash (Preview)",
        "id": "gemini-2.0-flash-001",
        "version": "gemini-2.0-flash-001",
        "object": "model",
        "policy": {
            "terms": "Enable access to the latest Gemini models from Google. [Learn more about how GitHub Copilot serves Gemini 2.0 Flash](https://docs.github.com/en/copilot/using-github-copilot/ai-models/using-gemini-flash-in-github-copilot).",
            "state": "enabled"
        }
    }
];


// 跨域响应头
function corsHeaders() {
    return {
        "Access-Control-Allow-Origin": "*",
        "Access-Control-Allow-Credentials": "true",
        "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
        "Access-Control-Allow-Headers": "Content-Type, Authorization",
        "Cache-Control": "no-cache",
        "Connection": "keep-alive"
    };
}

// 错误响应
function sendError(message, status) {
    const errorJson = { error: message };
    return new Response(JSON.stringify(errorJson), {
        status: status,
        headers: { ...corsHeaders(), "Content-Type": "application/json" }
    });
}

// 处理聊天补全请求
async function handleChatCompletions(request) {
    if (request.method === "OPTIONS") {
        return new Response(null, { status: 204, headers: corsHeaders() });
    }
    if (request.method === "GET") {
        const html = `<html><head><title>Welcome to API</title></head><body>
      <h1>Welcome to API</h1>
      <p>This API is used to interact with the GitHub Copilot model. You can send messages to the model and receive responses.</p>
      </body></html>`;
        return new Response(html, { status: 200, headers: { ...corsHeaders(), "Content-Type": "text/html; charset=utf-8" } });
    }
    if (request.method !== "POST") {
        return new Response(null, { status: 405, headers: corsHeaders() });
    }

    let token;
    try {
        token = await getTokenFromRequest(request);
    } catch (e) {
        return sendError("Token processing failed: " + e.message, 500);
    }
    if (!token) return sendError("Token is invalid.", 401);

    // 解析请求 JSON
    const reqJson = await request.json();
    const isStream = reqJson.stream || false;
    let model = reqJson.model || "gpt-4o";

    // 针对 o1/o3 系列的处理：关闭流式返回，但返回结果仍包装为 SSE 格式
    if (model.startsWith("o1") || model.startsWith("o3")) {
        reqJson.stream = false;
    } else {
        reqJson.stream = isStream;
    }

    const headersObj = getCompletionsHeaders(token);
    const apiUrl = "https://api.individual.githubcopilot.com/chat/completions";
    const init = {
        method: "POST",
        headers: headersObj,
        body: JSON.stringify(reqJson)
    };

    // 针对 o1/o3 系列，外部请求非流式，但返回构造 SSE 格式响应
    if (model.startsWith("o1") || model.startsWith("o3")) {
        const apiResp = await fetch(apiUrl, init);
        if (!apiResp.ok) {
            const errText = await apiResp.text();
            return sendError(`API Error: ${apiResp.status} - ${errText}`, apiResp.status);
        }
        const responseBody = await apiResp.text();
        let responseJson;
        try {
            responseJson = JSON.parse(responseBody);
        } catch (e) {
            return sendError("无法解析 API 响应 JSON: " + e.message, 500);
        }
        // 提取生成的内容
        let assistantContent = "";
        if (responseJson.choices && responseJson.choices.length > 0) {
            const firstChoice = responseJson.choices[0];
            if (firstChoice.message && firstChoice.message.content) {
                assistantContent = firstChoice.message.content;
            }
        }
        const openAIResponse = {
            id: "chatcmpl-" + crypto.randomUUID(),
            object: "chat.completion",
            created: Math.floor(Date.now() / 1000),
            model: responseJson.model || model,
            system_fingerprint: responseJson.system_fingerprint || ("fp_" + crypto.randomUUID().replace(/-/g, "").substring(0, 12)),
            choices: [{
                index: 0,
                message: { role: "assistant", content: assistantContent },
                finish_reason: "stop"
            }]
        };
        // 构造 SSE 格式的返回数据
        const sseLine = "data: " + JSON.stringify(openAIResponse) + "\n\n";
        return new Response(sseLine, {
            headers: {
                ...corsHeaders(),
                "Content-Type": "text/event-stream; charset=utf-8",
                "Cache-Control": "no-cache",
                "Connection": "keep-alive"
            }
        });
    } else if (isStream && reqJson.stream) {
        // 流式返回模式（对于非 o1/o3 模型）
        const apiResp = await fetch(apiUrl, init);
        if (!apiResp.ok) {
            const errText = await apiResp.text();
            return sendError(`API Error: ${apiResp.status} - ${errText}`, apiResp.status);
        }
        // 使用 TransformStream 处理 SSE 流
        const { readable, writable } = new TransformStream();
        (async () => {
            const writer = writable.getWriter();
            const reader = apiResp.body.getReader();
            const decoder = new TextDecoder("utf-8");
            let buffer = "";
            while (true) {
                const { done, value } = await reader.read();
                if (done) break;
                buffer += decoder.decode(value, { stream: true });
                const lines = buffer.split("\n");
                buffer = lines.pop(); // 保留不完整的行
                for (const line of lines) {
                    if (line.startsWith("data: ")) {
                        const data = line.substring(6).trim();
                        if (data === "[DONE]") {
                            writer.write(new TextEncoder().encode(line + "\n"));
                            await writer.close();
                            return;
                        }
                        try {
                            const sseJson = JSON.parse(data);
                            if (sseJson.choices) {
                                for (let i = 0; i < sseJson.choices.length; i++) {
                                    const choice = sseJson.choices[i];
                                    const delta = choice.delta;
                                    if (delta && delta.content) {
                                        const content = delta.content;
                                        if (content) {
                                            const newSseJson = {
                                                choices: [{
                                                    index: choice.index || i,
                                                    delta: { content }
                                                }],
                                                created: sseJson.created || Math.floor(Date.now() / 1000),
                                                id: sseJson.id || crypto.randomUUID(),
                                                model: sseJson.model || reqJson.model,
                                                system_fingerprint: sseJson.system_fingerprint || ("fp_" + crypto.randomUUID().replace(/-/g, "").substring(0, 12))
                                            };
                                            const newLine = "data: " + JSON.stringify(newSseJson) + "\n\n";
                                            writer.write(new TextEncoder().encode(newLine));
                                        }
                                    }
                                }
                            }
                        } catch (e) {
                            console.error("JSON parsing error: " + e.message);
                        }
                    }
                }
            }
            await writer.close();
        })();
        return new Response(readable, {
            headers: {
                ...corsHeaders(),
                "Content-Type": "text/event-stream; charset=utf-8",
                "Cache-Control": "no-cache",
                "Connection": "keep-alive"
            }
        });
    } else {
        // 普通模式（非流式返回）
        const apiResp = await fetch(apiUrl, init);
        if (!apiResp.ok) {
            const errText = await apiResp.text();
            return sendError(`API Error: ${apiResp.status} - ${errText}`, apiResp.status);
        }
        const responseBody = await apiResp.text();
        return new Response(responseBody, {
            status: 200,
            headers: { ...corsHeaders(), "Content-Type": "application/json" }
        });
    }
}



// 处理 Embedding 请求
async function handleEmbeddings(request) {
    if (request.method === "OPTIONS") {
        return new Response(null, { status: 204, headers: corsHeaders() });
    }
    if (request.method !== "POST") {
        return new Response(null, { status: 405, headers: corsHeaders() });
    }
    let token;
    try {
        token = await getTokenFromRequest(request);
    } catch (e) {
        return sendError("Token processing failed: " + e.message, 500);
    }
    if (!token) return sendError("Token is invalid.", 401);
    const reqJson = await request.json();
    console.log("Received Embedding Request JSON:");
    console.log(JSON.stringify(reqJson, null, 4));
    const headersObj = getEmbeddingsHeaders(token);
    const apiUrl = "https://api.individual.githubcopilot.com/embeddings";
    const init = {
        method: "POST",
        headers: headersObj,
        body: JSON.stringify(reqJson)
    };
    const apiResp = await fetch(apiUrl, init);
    const responseBody = await apiResp.text();
    console.log("Embedding Response:");
    try {
        console.log(JSON.stringify(JSON.parse(responseBody), null, 4));
    } catch (e) {
        console.log(responseBody);
    }
    if (apiResp.ok) {
        return new Response(responseBody, {
            status: 200,
            headers: { ...corsHeaders(), "Content-Type": "application/json; charset=utf-8" }
        });
    } else {
        return sendError(`Failed to get embeddings from Copilot API: ${responseBody}`, apiResp.status);
    }
}

// 处理 Models 请求
async function handleModels(request) {
    if (request.method === "OPTIONS") {
        return new Response(null, { status: 204, headers: corsHeaders() });
    }
    if (request.method !== "GET") {
        return new Response(null, { status: 405, headers: corsHeaders() });
    }
    let fetchedModels = defaultModels; // 默认使用静态模型列表
    const authHeader = request.headers.get("Authorization");
    if (authHeader) {
        const token = await getTokenFromRequest(request);
        if (token) {
            const headersObj = getModelHeaders(token);

            const apiUrl = "https://api.individual.githubcopilot.com/models";
            const init = { method: "GET", headers: headersObj };
            const apiResp = await fetch(apiUrl, init);
            if (apiResp.ok) {
                const json = await apiResp.json();
                fetchedModels = json.data;
            }
        }
    }
    const responseJson = { data: fetchedModels, object: "list" };
    return new Response(JSON.stringify(responseJson), {
        status: 200,
        headers: { ...corsHeaders(), "Content-Type": "application/json" }
    });
}

async function handleRequest(request) {
    const url = new URL(request.url);
    if (url.pathname.startsWith("/v1/chat/completions")) {
        return await handleChatCompletions(request);
    } else if (url.pathname.startsWith("/v1/embeddings")) {
        return await handleEmbeddings(request);
    } else if (url.pathname.startsWith("/v1/models")) {
        return await handleModels(request);
    } else {
        if (request.method === "GET") {
            const html = `<html><head><title>Welcome to API</title></head>
        <body><h1>Welcome to API</h1>
        <p>This API is used to interact with the GitHub Copilot model.</p></body></html>`;
            return new Response(html, { status: 200, headers: { "Content-Type": "text/html; charset=utf-8" } });
        }
        return new Response("Not found", { status: 404 });
    }
}

addEventListener("fetch", event => {
    event.respondWith(handleRequest(event.request));
});
