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

// CURL 回调函数，用于接收 HTTP 响应数据
size_t EmbeddingsResponseCallback(void* contents, size_t size, size_t nmemb, std::string* s) {
    size_t newLength = size * nmemb;
    try {
        s->reserve(s->size() + newLength);
        s->append((char*)contents, newLength);
        return newLength;
    } catch(std::bad_alloc& e) {
        return 0;
    }
}

// 创建并配置 HTTP 连接
CURL* createEmbeddingsConnection(struct curl_slist* headers, const std::string& jsonBody) {
    CURL* curl = curl_easy_init();
    if (!curl) {
        return nullptr;
    }
    curl_easy_setopt(curl, CURLOPT_BUFFERSIZE, 10240000L); // 10000KB

    // 设置 URL
    curl_easy_setopt(curl, CURLOPT_URL, "https://api.individual.githubcopilot.com/embeddings");
    
    // 打印请求头
    std::cout << "Embeddings Request Headers:" << std::endl;
    struct curl_slist* temp = headers;
    while (temp) {
        std::cout << temp->data << std::endl;
        temp = temp->next;
    }
    
    // 设置请求方法和超时
    curl_easy_setopt(curl, CURLOPT_POST, 1L);
    curl_easy_setopt(curl, CURLOPT_CONNECTTIMEOUT, 60L);
    curl_easy_setopt(curl, CURLOPT_TIMEOUT, 60L);
    
    // 禁用 SSL 证书验证以便抓包分析
    curl_easy_setopt(curl, CURLOPT_SSL_VERIFYPEER, 0L);
    curl_easy_setopt(curl, CURLOPT_SSL_VERIFYHOST, 0L);
    // 设置 HTTP 代理（如有需要）
   // curl_easy_setopt(curl, CURLOPT_PROXY, "127.0.0.1");
    //curl_easy_setopt(curl, CURLOPT_PROXYPORT, 5257L);

    // 设置请求头
    curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);
    
    // 设置请求体
    curl_easy_setopt(curl, CURLOPT_POSTFIELDS, jsonBody.c_str());
    
    return curl;
}

// 处理 embeddings 请求
void handleEmbeddings(const httplib::Request& req, httplib::Response& res) {
    // 设置 CORS 头
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
        
        // 获取长期 token
        std::string longTermToken = auth.substr(7);
        
        // 验证 token 前缀
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
        
        // 解析请求体
        json requestJson;
        try {
            requestJson = json::parse(req.body);
        } catch (const std::exception& e) {
            sendError(res, std::string("Invalid JSON: ") + e.what(), 400);
            return;
        }
        
        // 获取请求头
        struct curl_slist* headers = getHeaderManager().getApiHeaders(tempToken, HeaderManager::ApiType::EMBEDDINGS, requestJson);
        
        // 创建连接
        CURL* curl = createEmbeddingsConnection(headers, req.body);
        if (!curl) {
            sendError(res, "Failed to initialize CURL", 500);
            curl_slist_free_all(headers);
            return;
        }
        
        // 设置回调函数接收响应
        std::string responseString;
        curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, EmbeddingsResponseCallback);
        curl_easy_setopt(curl, CURLOPT_WRITEDATA, &responseString);
        
        // 执行请求
        CURLcode result = curl_easy_perform(curl);
        
        // 检查请求是否成功
        if (result != CURLE_OK) {
            sendError(res, std::string("CURL error: ") + curl_easy_strerror(result), 500);
            curl_slist_free_all(headers);
            curl_easy_cleanup(curl);
            return;
        }
        
        // 获取 HTTP 状态码
        long response_code;
        curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &response_code);
        
        // 设置响应头
        res.set_header("Content-Type", "application/json; charset=utf-8");
        
        if (response_code == 200) {
            // 返回成功响应
            res.status = 200;
            res.set_content(responseString, "application/json");
            
            // 输出日志
            std::cout << "Embeddings request successful" << std::endl;
        } else {
            // 返回错误响应
            res.status = response_code;
            res.set_content(responseString, "application/json");
            
            // 输出错误日志
            std::cerr << "Embeddings request failed with status code: " << response_code << std::endl;
            std::cerr << "Response: " << responseString << std::endl;
        }
        
        // 清理资源
        curl_slist_free_all(headers);
        curl_easy_cleanup(curl);
        
    } catch (const std::exception& e) {
        sendError(res, std::string("Internal server error: ") + e.what(), 500);
    }
}
