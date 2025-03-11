#include <iostream>
#include <string>
#include <httplib.h>
#include <curl/curl.h>
#include <nlohmann/json.hpp>
#include "header_manager.h"

// 声明 token_manager.cpp 中的函数（包含 getValidTempToken 的声明）
extern std::string getValidTempToken(const std::string& longTermToken);
extern void sendError(httplib::Response& res, const std::string& message, int HTTP_code);

using json = nlohmann::json;

// 回调函数：接收数据并追加到 std::string 中
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

// 创建并配置 CURL 连接（用于请求外部 /models API，采用 GET 请求）
CURL* createModelsConnection(struct curl_slist* headers) {
    CURL* curl = curl_easy_init();
    if (!curl) {
        return nullptr;
    }
    // 增大接收缓冲区
    curl_easy_setopt(curl, CURLOPT_BUFFERSIZE, 10240000L);
    // 设置请求 URL
    curl_easy_setopt(curl, CURLOPT_URL, "https://api.individual.githubcopilot.com/models");

    // 输出请求头（调试用）
    std::cout << "Models Request Headers:" << std::endl;
    for (struct curl_slist* h = headers; h; h = h->next) {
        std::cout << h->data << std::endl;
    }

    // 采用 GET 请求（默认即为 GET，无需设置 POST 选项）
    curl_easy_setopt(curl, CURLOPT_HTTPGET, 1L);
    curl_easy_setopt(curl, CURLOPT_CONNECTTIMEOUT, 60L);
    curl_easy_setopt(curl, CURLOPT_TIMEOUT, 60L);
    curl_easy_setopt(curl, CURLOPT_SSL_VERIFYPEER, 0L);
    curl_easy_setopt(curl, CURLOPT_SSL_VERIFYHOST, 0L);
    curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);

    return curl;
}

// 处理 /v1/models 请求（GET请求）
// 验证请求中的 Authorization 头、获取临时 token、生成请求头，发送 GET 请求给外部 API，最后将响应返回给客户端
void handleModels(const httplib::Request& req, httplib::Response& res) {
    // 设置响应头（参考 Java 实现）
    res.set_header("Access-Control-Allow-Origin", "*");
    res.set_header("Access-Control-Allow-Credentials", "true");
    res.set_header("Access-Control-Allow-Methods", "GET, OPTIONS");
    res.set_header("Access-Control-Allow-Headers", "Content-Type, Authorization");
    res.set_header("Cache-Control", "no-cache");
    res.set_header("Content-Type", "application/json; charset=utf-8");
    res.set_header("Connection", "keep-alive");

    try {
        // 验证 Authorization 头
        std::string auth = req.get_header_value("Authorization");
        if (auth.empty() || auth.substr(0, 7) != "Bearer ") {
            sendError(res, "Token is invalid.", 401);
            return;
        }
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

        // 此处对于 GET 请求无需解析请求体，直接使用 token 调用外部 API
        HeaderManager& hm = HeaderManager::getInstance();
        struct curl_slist* headers = hm.getApiHeaders(tempToken, HeaderManager::ApiType::MODELS, json{});

        // 创建 CURL 连接（采用 GET 请求）
        CURL* curl = createModelsConnection(headers);
        if (!curl) {
            sendError(res, "Failed to initialize CURL", 500);
            curl_slist_free_all(headers);
            return;
        }

        std::string responseString;
        curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, ResponseModelCallback);
        curl_easy_setopt(curl, CURLOPT_WRITEDATA, &responseString);

        // 执行 CURL 请求
        CURLcode result = curl_easy_perform(curl);
        if (result != CURLE_OK) {
            sendError(res, std::string("CURL error: ") + curl_easy_strerror(result), 500);
            curl_slist_free_all(headers);
            curl_easy_cleanup(curl);
            return;
        }

        long response_code;
        curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &response_code);

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

        curl_slist_free_all(headers);
        curl_easy_cleanup(curl);
    } catch (const std::exception& e) {
        sendError(res, std::string("Internal server error: ") + e.what(), 500);
    }
}
