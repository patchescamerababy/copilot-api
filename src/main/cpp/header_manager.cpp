#include "header_manager.h"
#include <curl/curl.h>
#include <string>

// 获取API请求头
struct curl_slist* HeaderManager::getApiHeaders(const std::string& token, HeaderManager::ApiType apiType, const json& requestJson) {
    struct curl_slist* headers = nullptr;

    // 添加基本请求头
    addBasicHeaders(&headers);

    // 添加安全相关请求头
    addSecurityHeaders(&headers);

    // 根据 API 类型添加特定请求头
    switch (apiType) {
        case ApiType::CHAT_COMPLETIONS:
            headers = curl_slist_append(headers, "openai-intent: conversation-panel");
            // 若存在图像内容，则设置 copilot-vision-request
            if (hasImageContent(requestJson)) {
                headers = curl_slist_append(headers, "copilot-vision-request: true");
            } else {
                headers = curl_slist_append(headers, "copilot-vision-request: false");
            }
            break;
        case ApiType::EMBEDDINGS:
            headers = curl_slist_append(headers, "openai-intent: embeddings");
            break;
        case ApiType::MODELS:
            // 针对模型 API，使用 model-access，不添加 copilot-vision-request
            headers = curl_slist_append(headers, "openai-intent: model-access");
            break;
        default:
            break;
    }

    // 添加授权头（默认使用 Bearer 格式）
    addAuthorizationHeader(&headers, token);
    return headers;
}

// 获取Token API请求头
struct curl_slist* HeaderManager::getTokenHeaders(const std::string& longTermToken) {
    struct curl_slist* headers = nullptr;

    headers = curl_slist_append(headers, "Editor-Plugin-Version: copilot/1.270.0");
    headers = curl_slist_append(headers, "Editor-Version: vscode/1.98.0-insider");
    headers = curl_slist_append(headers, "User-Agent: GitHubCopilotChat/0.23.2");
    headers = curl_slist_append(headers, "x-github-api-version: 2025-01-21");

    // 添加安全相关请求头
    headers = curl_slist_append(headers, "Sec-Fetch-Site: none");
    headers = curl_slist_append(headers, "Sec-Fetch-Mode: no-cors");
    headers = curl_slist_append(headers, "Sec-Fetch-Dest: empty");

    // 添加授权头（使用长期 token，此处以 token 格式添加）
    addAuthorizationHeader(&headers, longTermToken, true);
    return headers;
}

// 添加基本请求头
void HeaderManager::addBasicHeaders(struct curl_slist** headers) {
    *headers = curl_slist_append(*headers, "Content-Type: application/json");
    *headers = curl_slist_append(*headers, "Connection: keep-alive");
    *headers = curl_slist_append(*headers, "Editor-Plugin-Version: copilot/1.270.0");
    *headers = curl_slist_append(*headers, "Editor-Version: vscode/1.98.0-insider");
    *headers = curl_slist_append(*headers, "Openai-Organization: github-copilot");
    *headers = curl_slist_append(*headers, "User-Agent: GitHubCopilotChat/0.23.2");
    *headers = curl_slist_append(*headers, "accept: */*");
    *headers = curl_slist_append(*headers, "accept-encoding: gzip, deflate, br, zstd");
    *headers = curl_slist_append(*headers, "X-GitHub-Api-Version: 2025-01-21");
}

// 添加安全相关请求头
void HeaderManager::addSecurityHeaders(struct curl_slist** headers) {
    *headers = curl_slist_append(*headers, "Sec-Fetch-Site: none");
    *headers = curl_slist_append(*headers, "Sec-Fetch-Mode: no-cors");
    *headers = curl_slist_append(*headers, "Sec-Fetch-Dest: empty");
}

// 添加授权头
void HeaderManager::addAuthorizationHeader(struct curl_slist** headers, const std::string& token, bool isLongTermToken) {
    std::string authHeader;
    if (isLongTermToken) {
        authHeader = "Authorization: token " + token;
    } else {
        authHeader = "Authorization: Bearer " + token;
    }
    *headers = curl_slist_append(*headers, authHeader.c_str());
}

// 检查请求是否包含图像
bool HeaderManager::hasImageContent(const json& requestJson) {
    if (requestJson.contains("messages") && requestJson["messages"].is_array()) {
        const auto& messages = requestJson["messages"];
        for (const auto& message : messages) {
            if (message.contains("content")) {
                const auto& contentObj = message["content"];
                if (contentObj.is_array()) {
                    for (const auto& contentItem : contentObj) {
                        if (contentItem.contains("type") &&
                            contentItem["type"] == "image_url" &&
                            contentItem.contains("image_url")) {
                            return true;
                        }
                    }
                }
            }
        }
    }
    return false;
}
