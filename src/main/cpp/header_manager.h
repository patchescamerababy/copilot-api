#ifndef HEADER_MANAGER_H
#define HEADER_MANAGER_H

#include <string>
#include <curl/curl.h>
#include <nlohmann/json.hpp>

using json = nlohmann::json;

// 请求头管理器类，负责为不同 API 端点生成适当的请求头
class HeaderManager {
public:
    // 请求类型枚举
    enum class ApiType {
        CHAT_COMPLETIONS,
        EMBEDDINGS,
        MODELS,
        TOKEN
    };

    HeaderManager() = default;
    ~HeaderManager() = default;

    // 获取单例实例
    static HeaderManager& getInstance() {
        static HeaderManager instance;
        return instance;
    }

    // 获取 API 请求头
    struct curl_slist* getApiHeaders(const std::string& token, ApiType apiType);

    // 获取 Token API 请求头
    struct curl_slist* getTokenHeaders(const std::string& longTermToken);

private:
    // 生成随机十六进制字符串
    std::string generateRandomHex(int length);

    // 生成随机UUID格式字符串
    std::string generateRandomXRequestId(int length);

    // 获取会话ID
    std::string getSessionId();

    // 生成随机UUID
    std::string generateRandomUuid();

    // 获取机器ID
    std::string getMachineId();
    // 添加基本请求头
    void addBasicHeaders(struct curl_slist** headers);

    // 添加安全相关请求头
    void addSecurityHeaders(struct curl_slist** headers);

    // 添加授权头
    void addAuthorizationHeader(struct curl_slist** headers, const std::string& token, bool isLongTermToken = false);

    // 检查请求是否包含图像
    bool hasImageContent(const json& requestJson);
};

#endif // HEADER_MANAGER_H
