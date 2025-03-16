#include "header_manager.h"
#include <curl/curl.h>
#include <string>
#include <random>
#include <sstream>

// 生成随机十六进制字符串（用于VScode-MachineId）
std::string HeaderManager::generateRandomHex(int length) {
    static const char hexChars[] = "0123456789abcdef";
    std::random_device rd;
    std::mt19937 gen(rd());
    std::uniform_int_distribution<> dis(0, 15);

    std::string result;
    result.reserve(length);

    for (int i = 0; i < length; ++i) {
        result.push_back(hexChars[dis(gen)]);
    }

    return result;
}

// 生成随机UUID格式的字符串（用于X-Request-Id）
std::string HeaderManager::generateRandomXRequestId(int length) {
    static const char chars[] = "abcdefghijklmnopqrstuvwxyz0123456789";
    std::random_device rd;
    std::mt19937 gen(rd());
    std::uniform_int_distribution<> dis(0, 35);

    std::string result;
    result.reserve(length);

    for (int i = 0; i < length; ++i) {
        result.push_back(chars[dis(gen)]);
    }

    // 格式化为 UUID 格式 xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
    std::stringstream ss;
    ss << result.substr(0, 8) << "-"
        << result.substr(8, 4) << "-"
        << result.substr(12, 4) << "-"
        << result.substr(16, 4) << "-"
        << result.substr(20, 12);

    return ss.str();
}

// 获取会话ID（用于VScode-SessionId）
std::string HeaderManager::getSessionId() {
    static std::string sessionId;
    if (sessionId.empty()) {
        // 生成UUID
        std::random_device rd;
        std::mt19937 gen(rd());
        std::uniform_int_distribution<long long> dis(0, 9999999999999);

        std::stringstream ss;
        ss << generateRandomUuid() << dis(gen);
        sessionId = ss.str();
    }
    return sessionId;
}

// 生成UUID
std::string HeaderManager::generateRandomUuid() {
    static const char hexChars[] = "0123456789abcdef";
    std::random_device rd;
    std::mt19937 gen(rd());
    std::uniform_int_distribution<> dis(0, 15);

    std::stringstream ss;

    for (int i = 0; i < 8; ++i) ss << hexChars[dis(gen)];
    ss << "-";
    for (int i = 0; i < 4; ++i) ss << hexChars[dis(gen)];
    ss << "-";
    for (int i = 0; i < 4; ++i) ss << hexChars[dis(gen)];
    ss << "-";
    for (int i = 0; i < 4; ++i) ss << hexChars[dis(gen)];
    ss << "-";
    for (int i = 0; i < 12; ++i) ss << hexChars[dis(gen)];

    return ss.str();
}

// 获取 VScode-MachineId
std::string HeaderManager::getMachineId() {
    static std::string machineId = generateRandomHex(64);
    return machineId;
}

// 获取API请求头
struct curl_slist* HeaderManager::getApiHeaders(const std::string& token, HeaderManager::ApiType apiType) {
    struct curl_slist* headers = nullptr;

    // 添加基本请求头
    addBasicHeaders(&headers);

    // 添加安全相关请求头
    addSecurityHeaders(&headers);

    // 根据 API 类型添加特定请求头
    switch (apiType) {
    case ApiType::CHAT_COMPLETIONS:
        headers = curl_slist_append(headers, "openai-intent: conversation-panel");
        // // 若存在图像内容，则设置 copilot-vision-request
        // if (hasImageContent(requestJson)) {
        //     headers = curl_slist_append(headers, "copilot-vision-request: true");
        // } else {
        //     headers = curl_slist_append(headers, "copilot-vision-request: false");
        // }
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

    headers = curl_slist_append(headers, "editor-plugin-version: copilot/1.270.0");
    headers = curl_slist_append(headers, "editor-version: vscode/1.98.0-insider");
    headers = curl_slist_append(headers, "user-agent: GitHubCopilotChat/0.23.2");
    headers = curl_slist_append(headers, "x-github-api-version: 2025-01-21");

    // 添加VScode-MachineId和VScode-SessionId
    headers = curl_slist_append(headers, ("vscode-machineid: " + getMachineId()).c_str());
    headers = curl_slist_append(headers, ("vscode-sessionid: " + getSessionId()).c_str());

    // 添加安全相关请求头
    headers = curl_slist_append(headers, "sec-fetch-site: none");
    headers = curl_slist_append(headers, "sec-fetch-mode: no-cors");
    headers = curl_slist_append(headers, "sec-fetch-dest: empty");

    // 添加授权头（使用长期 token，此处以 token 格式添加）
    addAuthorizationHeader(&headers, longTermToken, true);
    return headers;
}

// 添加基本请求头
void HeaderManager::addBasicHeaders(struct curl_slist** headers) {
    *headers = curl_slist_append(*headers, "content-type: application/json");
    *headers = curl_slist_append(*headers, "connection: keep-alive");
    *headers = curl_slist_append(*headers, "editor-plugin-version: copilot/1.270.0");
    *headers = curl_slist_append(*headers, "editor-version: vscode/1.98.0-insider");
    *headers = curl_slist_append(*headers, "openai-organization: github-copilot");
    *headers = curl_slist_append(*headers, "user-agent: GitHubCopilotChat/0.23.2");
    *headers = curl_slist_append(*headers, "accept: */*");
    *headers = curl_slist_append(*headers, "accept-encoding: gzip, deflate, br, zstd");
    *headers = curl_slist_append(*headers, "x-github-api-version: 2025-01-21");

    // 添加VScode-MachineId和VScode-SessionId
    *headers = curl_slist_append(*headers, ("vscode-machineid: " + getMachineId()).c_str());
    *headers = curl_slist_append(*headers, ("vscode-sessionid: " + getSessionId()).c_str());

    // 添加X-Request-Id
    *headers = curl_slist_append(*headers, ("x-request-id: " + generateRandomXRequestId(32)).c_str());
}

// 添加安全相关请求头
void HeaderManager::addSecurityHeaders(struct curl_slist** headers) {
    *headers = curl_slist_append(*headers, "sec-fetch-site: none");
    *headers = curl_slist_append(*headers, "sec-fetch-mode: no-cors");
    *headers = curl_slist_append(*headers, "sec-fetch-dest: empty");
}

// 添加授权头
void HeaderManager::addAuthorizationHeader(struct curl_slist** headers, const std::string& token, bool isLongTermToken) {
    std::string authHeader;
    if (isLongTermToken) {
        authHeader = "authorization: token " + token;
    }
    else {
        authHeader = "authorization: Bearer " + token;
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
