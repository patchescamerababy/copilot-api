#include "header_manager.h"
#include <iostream>

// 获取API请求头
struct curl_slist* HeaderManager::getApiHeaders(const std::string& token, HeaderManager::ApiType apiType, const json& requestJson) {
    struct curl_slist* headers = nullptr;
    
    // 添加基本请求头
    addBasicHeaders(&headers);
    
    // 添加安全相关请求头
    addSecurityHeaders(&headers);
    
    // 根据API类型添加特定请求头
    switch (apiType) {
        case HeaderManager::ApiType::CHAT_COMPLETIONS:
            headers = curl_slist_append(headers, "openai-intent: conversation-panel");
            
            // 检查是否包含图像并设置相应的请求头
            if (hasImageContent(requestJson)) {
                headers = curl_slist_append(headers, "copilot-vision-request: true");
            } else {
                headers = curl_slist_append(headers, "copilot-vision-request: false");
            }
            break;
            
        case HeaderManager::ApiType::EMBEDDINGS:
            // 嵌入API特定的请求头
            headers = curl_slist_append(headers, "openai-intent: embeddings");
            break;
            
        case HeaderManager::ApiType::MODELS:
            // 模型API特定的请求头
            headers = curl_slist_append(headers, "openai-intent: models-list");
            break;
            
        default:
            // 默认不添加特定请求头
            break;
    }
    
    // 添加授权头
    addAuthorizationHeader(&headers, token);
    
    return headers;
}

// 获取Token API请求头
struct curl_slist* HeaderManager::getTokenHeaders(const std::string& longTermToken) {
    struct curl_slist* headers = nullptr;
    
    // 添加基本请求头
    headers = curl_slist_append(headers, "Editor-Plugin-Version: copilot/1.270.0");
    headers = curl_slist_append(headers, "Editor-Version: vscode/1.98.0-insider");
    headers = curl_slist_append(headers, "User-Agent: GitHubCopilotChat/0.23.2");
    headers = curl_slist_append(headers, "x-github-api-version: 2025-01-21");
    
    // 添加安全相关请求头
    headers = curl_slist_append(headers, "Sec-Fetch-Site: none");
    headers = curl_slist_append(headers, "Sec-Fetch-Mode: no-cors");
    headers = curl_slist_append(headers, "Sec-Fetch-Dest: empty");
    
    // 添加授权头（使用长期token）
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
    // 检查是否存在messages数组
    if (requestJson.contains("messages") && requestJson["messages"].is_array()) {
        const auto& messages = requestJson["messages"];
        
        // 遍历所有消息
        for (const auto& message : messages) {
            // 检查消息是否包含content字段
            if (message.contains("content")) {
                const auto& contentObj = message["content"];
                
                // 检查content是否为数组类型
                if (contentObj.is_array()) {
                    const auto& contentArray = contentObj;
                    
                    // 遍历content数组中的每个项
                    for (const auto& contentItem : contentArray) {
                        // 检查项是否包含type字段
                        if (contentItem.contains("type")) {
                            // 检查type是否为image_url且包含image_url字段
                            if (contentItem["type"] == "image_url" && contentItem.contains("image_url")) {
                                return true;
                            }
                        }
                    }
                }
                // 注意：Java代码中没有处理content为字符串的情况，因为图像只会出现在数组类型的content中
            }
        }
    }
    return false;
}
