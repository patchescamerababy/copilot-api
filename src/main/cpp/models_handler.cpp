// model_handler.cpp
#include <iostream>
#include <string>
#include <httplib.h>
#include <curl/curl.h>
#include <nlohmann/json.hpp>
#include "header_manager.h"

// 引入 token_manager.cpp 中的函数声明
extern std::string getValidTempToken(const std::string& longTermToken);
extern void sendError(httplib::Response& res, const std::string& message, int HTTP_code);
extern HeaderManager& getHeaderManager();

using json = nlohmann::json;

// 与 completion_handler 中一致的回调函数，接收数据并追加到 std::string 中
size_t ResponseModelCallback(void* contents, size_t size, size_t nmemb, std::string* s) {
    size_t newLength = size * nmemb;
    try {
        s->reserve(s->size() + newLength);
        s->append(static_cast<char*>(contents), newLength);
        return newLength;
    } catch (std::bad_alloc& e) {
        return 0;
    }
}

// 创建并配置 HTTP 连接
CURL* createModelsConnection(struct curl_slist* headers) {
    CURL* curl = curl_easy_init();
    if (!curl) {
        return nullptr;
    }
    // 增大 libcurl 接收缓冲区大小（此处设定为 100KB）
    curl_easy_setopt(curl, CURLOPT_BUFFERSIZE, 10240000L);
    // 设置请求 URL
    curl_easy_setopt(curl, CURLOPT_URL, "https://api.individual.githubcopilot.com/models");

    // 打印请求头（便于调试）
    std::cout << "Models Request Headers:" << std::endl;
    struct curl_slist* temp = headers;
    while (temp) {
        std::cout << temp->data << std::endl;
        temp = temp->next;
    }

    // 设置 POST 请求和超时参数
    curl_easy_setopt(curl, CURLOPT_POST, 1L);
    curl_easy_setopt(curl, CURLOPT_CONNECTTIMEOUT, 60L);
    curl_easy_setopt(curl, CURLOPT_TIMEOUT, 60L);

    // 禁用 SSL 证书验证（便于抓包分析）
    curl_easy_setopt(curl, CURLOPT_SSL_VERIFYPEER, 0L);
    curl_easy_setopt(curl, CURLOPT_SSL_VERIFYHOST, 0L);
    // 设置 HTTP 代理（如有需要）
    //curl_easy_setopt(curl, CURLOPT_PROXY, "127.0.0.1");
    //curl_easy_setopt(curl, CURLOPT_PROXYPORT, 5257L);

    // 设置请求头
    curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);

    return curl;
}

// 处理 models 请求的主函数
void handleModels(const httplib::Request& req, httplib::Response& res) {
    // 设置 CORS 和连接相关的响应头
    res.set_header("Access-Control-Allow-Origin", "*");
    res.set_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    res.set_header("Access-Control-Allow-Headers", "Content-Type, Authorization");
    res.set_header("Connection", "keep-alive");

    try {
        // 验证 Authorization 头
        std::string auth = req.get_header_value("Authorization");
        if (auth.empty() || auth.substr(0, 7) != "Bearer ") {
            sendError(res, "Token is invalid.", 401);
            return;
        }
        // 提取长期 token，并验证 token 前缀
        std::string longTermToken = auth.substr(7);
        if (!(longTermToken.substr(0, 3) == "ghu" || longTermToken.substr(0, 3) == "gho")) {
            sendError(res, "Invalid token prefix.", 401);
            return;
        }
        // 获取有效的临时 token
        std::string tempToken;
        try {
            tempToken = getValidTempToken(longTermToken);
        } catch (const std::exception& e) {
            sendError(res, std::string("Token processing failed: ") + e.what(), 500);
            return;
        }
        if (tempToken.empty()) {
            sendError(res, "Unable to obtain a valid temporary token.", 500);
            return;
        }

        // 获取请求头（此处依赖 HeaderManager 中的 getApiHeaders 方法）
        struct curl_slist* headers = getHeaderManager().getApiHeaders(tempToken, HeaderManager::ApiType::MODELS);

        // 创建连接
        CURL* curl = createModelsConnection(headers);
        if (!curl) {
            sendError(res, "Failed to initialize CURL", 500);
            curl_slist_free_all(headers);
            return;
        }

        // 设置回调函数，使用与 completion_handler 中一致的 ResponseCallback
        std::string responseString;
        curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, ResponseModelCallback);
        curl_easy_setopt(curl, CURLOPT_WRITEDATA, &responseString);

        // 如果 models 接口要求请求体，请确保设置（例如：curl_easy_setopt(curl, CURLOPT_POSTFIELDS, "{}");）
        // curl_easy_setopt(curl, CURLOPT_POSTFIELDS, "{}");

        // 执行请求
        CURLcode result = curl_easy_perform(curl);
        if (result != CURLE_OK) {
            sendError(res, std::string("CURL error: ") + curl_easy_strerror(result), 500);
            curl_slist_free_all(headers);
            curl_easy_cleanup(curl);
            return;
        }

        // 获取 HTTP 状态码
        long response_code;
        curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &response_code);

        // 设置响应头，返回 JSON 格式数据
        res.set_header("Content-Type", "application/json; charset=utf-8");

        if (response_code == 200) {
            res.status = 200;
            res.set_content(responseString, "application/json");
            std::cout << "Models request successful" << std::endl;
        } else {
            res.status = response_code;
            res.set_content(responseString, "application/json");
            std::cerr << "Models request failed with status code: " << response_code << std::endl;
            std::cerr << "Response: " << responseString << std::endl;
        }

        // 清理资源
        curl_slist_free_all(headers);
        curl_easy_cleanup(curl);
    } catch (const std::exception& e) {
        sendError(res, std::string("Internal server error: ") + e.what(), 500);
    }
}
