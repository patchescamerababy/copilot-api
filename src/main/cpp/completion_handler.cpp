//completion_handler.cpp
#include <iostream>
#include <string>
#include <vector>
#include <mutex>
#include <chrono>
#include <httplib.h>
#include <curl/curl.h>
#include <nlohmann/json.hpp>
#include "header_manager.h"

// 引入 token_manager.cpp 中的函数声明
extern std::string getValidTempToken(const std::string& longTermToken);
extern void sendError(httplib::Response& res, const std::string& message, int HTTP_code);
extern HeaderManager& getHeaderManager();

using json = nlohmann::json;
// CURL 回调函数，用于接收 HTTP 响应数据（非流式响应可用）
size_t ResponseCallback(void* contents, size_t size, size_t nmemb, std::string* s) {
    size_t newLength = size * nmemb;
    try {
        s->reserve(s->size() + newLength);
        s->append(static_cast<char*>(contents), newLength);
        return newLength;
    } catch(std::bad_alloc& e) {
        return 0;
    }
}

// 流式回调上下文，增加了 accumulated 成员用于累计数据
struct StreamContext {
    httplib::Response* res;
    bool isO1;
    std::string model;
    std::string accumulated;  // 用于累计接收到的数据
};

// 修改后的流式回调函数：将每块数据追加到 accumulated 中
size_t StreamResponseCallback(void* contents, size_t size, size_t nmemb, void* userp) {
    size_t realsize = size * nmemb;
    StreamContext* context = static_cast<StreamContext*>(userp);

    // 将当前接收到的数据追加到 accumulated 中
    context->accumulated.append(static_cast<char*>(contents), realsize);

    // 此处不再调用 res.set_content()，避免覆盖前面的数据
    return realsize;
}


// 获取 Copilot API 的请求头
struct curl_slist* getCopilotHeaders(const std::string& token, bool hasImage = false) {
    struct curl_slist* headers = nullptr;
    // 设置基本请求头
    headers = curl_slist_append(headers, "Content-Type: application/json");
    headers = curl_slist_append(headers, "Connection: keep-alive");
    headers = curl_slist_append(headers, "Editor-Plugin-Version: copilot/1.270.0");
    headers = curl_slist_append(headers, "Editor-Version: vscode/1.98.0-insider");
    headers = curl_slist_append(headers, "Openai-Organization: github-copilot");
    headers = curl_slist_append(headers, "User-Agent: GitHubCopilotChat/0.23.2");
    headers = curl_slist_append(headers, "accept: */*");
    headers = curl_slist_append(headers, "Sec-Fetch-Site: none");
    headers = curl_slist_append(headers, "Sec-Fetch-Mode: no-cors");
    headers = curl_slist_append(headers, "Sec-Fetch-Dest: empty");
    headers = curl_slist_append(headers, "accept-encoding: gzip, deflate, br, zstd");
    headers = curl_slist_append(headers, "X-GitHub-Api-Version: 2025-01-21");

    // 设置特殊请求头
    headers = curl_slist_append(headers, "openai-intent: conversation-panel");

    // 设置图像请求头
    if (hasImage) {
        headers = curl_slist_append(headers, "copilot-vision-request: true");
    } else {
        headers = curl_slist_append(headers, "copilot-vision-request: false");
    }

    // 设置授权头
    std::string authHeader = "Authorization: Bearer " + token;
    headers = curl_slist_append(headers, authHeader.c_str());

    return headers;
}

// 创建并配置 HTTP 连接
CURL* createConnection(struct curl_slist* headers, const std::string& jsonBody) {
    CURL* curl = curl_easy_init();
    if (!curl) {
        return nullptr;
    }

    // 增加 libcurl 接收缓冲区大小
    curl_easy_setopt(curl, CURLOPT_BUFFERSIZE, 102400L); // 设定为 100KB

    // 打印请求 JSON
    std::cout << "Request JSON: " << jsonBody << std::endl;

    // 打印请求头
    std::cout << "Request Headers:" << std::endl;
    struct curl_slist* temp = headers;
    while (temp) {
        std::cout << temp->data << std::endl;
        temp = temp->next;
    }

    // 设置 URL
    curl_easy_setopt(curl, CURLOPT_URL, "https://api.individual.githubcopilot.com/chat/completions");

    // 设置请求方法和超时
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

    // 设置请求体
    curl_easy_setopt(curl, CURLOPT_POSTFIELDS, jsonBody.c_str());

    return curl;
}



// 处理普通响应
void handleNormalResponse(httplib::Response& res, const std::string& token, const json& requestJson) {
    try {
        // 获取请求头
        struct curl_slist* headers = getHeaderManager().getApiHeaders(token, HeaderManager::ApiType::CHAT_COMPLETIONS, requestJson);

        // 创建连接，注意这里将 dump 后的 JSON 存储在局部变量中，确保内存有效
        std::string jsonBody = requestJson.dump();
        CURL* curl = createConnection(headers, jsonBody);
        if (!curl) {
            sendError(res, "Failed to initialize CURL", 500);
            curl_slist_free_all(headers);
            return;
        }

        // 设置回调函数接收响应
        std::string responseString;
        curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, ResponseCallback);
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

        if (response_code != 200) {
            sendError(res, responseString, response_code);
            curl_slist_free_all(headers);
            curl_easy_cleanup(curl);
            return;
        }

        // 设置响应头和响应内容
        res.set_header("Content-Type", "application/json; charset=utf-8");
        res.status = 200;
        res.set_content(responseString, "application/json");

        // 清理资源
        curl_slist_free_all(headers);
        curl_easy_cleanup(curl);
    } catch (const std::exception& e) {
        sendError(res, std::string("Internal server error: ") + e.what(), 500);
    }
}

// 处理流式响应

// 修改后的 handleStreamResponse 函数
void handleStreamResponse(httplib::Response& res, const std::string& token, const json& requestJson, bool isO1) {
    try {
        // 获取请求头
        struct curl_slist* headers = getHeaderManager().getApiHeaders(token, HeaderManager::ApiType::CHAT_COMPLETIONS, requestJson);

        // 创建连接，确保 requestJson.dump() 保存在局部变量中
        std::string jsonBody = requestJson.dump();
        CURL* curl = curl_easy_init();
        if (!curl) {
            sendError(res, "Failed to initialize CURL", 500);
            curl_slist_free_all(headers);
            return;
        }

        // 设置 URL、请求方法、超时、代理等参数（可参考原代码）
        curl_easy_setopt(curl, CURLOPT_URL, "https://api.individual.githubcopilot.com/chat/completions");
        curl_easy_setopt(curl, CURLOPT_POST, 1L);
        curl_easy_setopt(curl, CURLOPT_CONNECTTIMEOUT, 60L);
        curl_easy_setopt(curl, CURLOPT_TIMEOUT, 60L);
        curl_easy_setopt(curl, CURLOPT_SSL_VERIFYPEER, 0L);
        curl_easy_setopt(curl, CURLOPT_SSL_VERIFYHOST, 0L);
        curl_easy_setopt(curl, CURLOPT_PROXY, "127.0.0.1");
        curl_easy_setopt(curl, CURLOPT_PROXYPORT, 5257L);
        curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);
        curl_easy_setopt(curl, CURLOPT_POSTFIELDS, jsonBody.c_str());
        // 可选：增大缓冲区
        curl_easy_setopt(curl, CURLOPT_BUFFERSIZE, 102400L);

        // 如果是 O1 模型，直接调用普通响应处理
        if (isO1) {
            // 调用普通响应处理（该函数内部也应累加数据或直接返回完整响应）
            // 这里只做示例，所以直接清理资源后返回
            curl_slist_free_all(headers);
            curl_easy_cleanup(curl);
            handleStreamResponse(res, token, requestJson, false);  // 或调用 handleNormalResponse()
            return;
        }

        // 设置流式响应头
        res.set_header("Content-Type", "text/event-stream; charset=utf-8");
        res.set_header("Cache-Control", "no-cache");
        res.set_header("Connection", "keep-alive");

        // 初始化流式回调上下文，并清空累计缓冲区
        StreamContext context;
        context.res = &res;
        context.isO1 = isO1;
        context.model = requestJson.value("model", "gpt-4");
        context.accumulated = "";

        // 设置回调函数和数据存储上下文
        curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, StreamResponseCallback);
        curl_easy_setopt(curl, CURLOPT_WRITEDATA, &context);

        // 执行请求，期间回调函数会不断追加数据到 context.accumulated 中
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
        if (response_code != 200) {
            sendError(res, "Error occurred in streaming response", response_code);
        } else {
            // 将累计的数据一次性返回给客户端
            res.set_content(context.accumulated, "text/event-stream");
        }

        // 清理资源
        curl_slist_free_all(headers);
        curl_easy_cleanup(curl);
    } catch (const std::exception& e) {
        sendError(res, std::string("Error occurred while processing stream response: ") + e.what(), 500);
    }
}