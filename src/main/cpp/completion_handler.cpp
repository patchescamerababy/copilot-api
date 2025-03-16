// completion_handler.cpp

#include <iostream>
#include <string>
#include <vector>
#include <mutex>
#include <chrono>
#include <queue>
#include <thread>
#include <condition_variable>
#include <memory>
#include <httplib.h>
#include <curl/curl.h>
#include <nlohmann/json.hpp>
#include "header_manager.h"

// 外部函数声明
extern std::string getValidTempToken(const std::string& longTermToken);

extern void sendError(httplib::Response& res, const std::string& message, int HTTP_code);

extern HeaderManager& getHeaderManager();

using json = nlohmann::json;

// 用于 SSE 流式响应中保存待发送数据的线程安全共享缓冲区
struct SharedBuffer {
    std::queue<std::string> chunks;
    std::mutex mtx;
    std::condition_variable cv;
    bool finished = false;
};

// 静态 thread_local 变量，用于在不捕获的 lambda 中传递 SharedBuffer 指针
static thread_local SharedBuffer* tls_sharedBuffer = nullptr;

// 普通响应的回调函数：将接收到的所有数据累加到 responseString 中
size_t ResponseCallback(void* contents, size_t size, size_t nmemb, std::string* s) {
    size_t newLength = size * nmemb;
    try {
        s->reserve(s->size() + newLength);
        s->append(static_cast<char*>(contents), newLength);
        return newLength;
    }
    catch (std::bad_alloc& e) {
        return 0;
    }
}

// 流式回调函数（供 curl 调用）：
// 将接收到的数据追加到线程局部的累计缓冲区，遇到换行符时将该行（封装为 SSE 格式）写入 SharedBuffer
size_t StreamResponseCallback(void* contents, size_t size, size_t nmemb, void* userp) {
    size_t realsize = size * nmemb;
    SharedBuffer* sharedBuffer = static_cast<SharedBuffer*>(userp);
    // 使用线程局部变量存储累计数据
    static thread_local std::string accumulated;
    std::string data(static_cast<char*>(contents), realsize);
    accumulated.append(data);
    size_t pos = 0;
    // 每遇到一个换行符则认为接收到一行完整数据
    while ((pos = accumulated.find("\n")) != std::string::npos) {
        std::string line = accumulated.substr(0, pos + 1); {
            std::lock_guard<std::mutex> lock(sharedBuffer->mtx);
            sharedBuffer->chunks.push(line);
        }
        sharedBuffer->cv.notify_one();
        accumulated.erase(0, pos + 1);
    }
    return realsize;
}

// 创建并配置普通请求的 CURL 连接（非流式）
CURL* createConnection(struct curl_slist* headers, const std::string& jsonBody) {
    CURL* curl = curl_easy_init();
    if (!curl) {
        return nullptr;
    }
    curl_easy_setopt(curl, CURLOPT_BUFFERSIZE, 102400L);
    //std::cout << "Request JSON: " << jsonBody << std::endl;
    //std::cout << "Request Headers:" << std::endl;
    for (struct curl_slist* temp = headers; temp; temp = temp->next) {
        std::cout << temp->data << std::endl;
    }
    curl_easy_setopt(curl, CURLOPT_URL, "https://api.individual.githubcopilot.com/chat/completions");
    curl_easy_setopt(curl, CURLOPT_POST, 1L);
    curl_easy_setopt(curl, CURLOPT_CONNECTTIMEOUT, 60L);
    curl_easy_setopt(curl, CURLOPT_TIMEOUT, 60L);
    curl_easy_setopt(curl, CURLOPT_SSL_VERIFYPEER, 0L);
    curl_easy_setopt(curl, CURLOPT_SSL_VERIFYHOST, 0L);
    //curl_easy_setopt(curl, CURLOPT_PROXY, "127.0.0.1");
    //curl_easy_setopt(curl, CURLOPT_PROXYPORT, 5257L);
    curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);
    curl_easy_setopt(curl, CURLOPT_POSTFIELDS, jsonBody.c_str());
    return curl;
}

// 处理普通（非流式）响应
void handleNormalResponse(httplib::Response& res, const std::string& token, const json& requestJson, bool hasImage) {
    try {

        struct curl_slist* headers = getHeaderManager().getApiHeaders(token, HeaderManager::ApiType::CHAT_COMPLETIONS);
        if (hasImage) {
            headers = curl_slist_append(headers, "copilot-vision-request: true");
        }
        else {
            headers = curl_slist_append(headers, "copilot-vision-request: fasle");
        }
        std::string jsonBody = requestJson.dump();
        CURL* curl = createConnection(headers, jsonBody);
        if (!curl) {
            sendError(res, "Failed to initialize CURL", 500);
            curl_slist_free_all(headers);
            return;
        }
        std::string responseString;
        curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, ResponseCallback);
        curl_easy_setopt(curl, CURLOPT_WRITEDATA, &responseString);
        CURLcode result = curl_easy_perform(curl);
        if (result != CURLE_OK) {
            sendError(res, std::string("CURL error: ") + curl_easy_strerror(result), 500);
            curl_slist_free_all(headers);
            curl_easy_cleanup(curl);
            return;
        }
        long response_code;
        curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &response_code);
        if (response_code != 200) {
            sendError(res, responseString, response_code);
            curl_slist_free_all(headers);
            curl_easy_cleanup(curl);
            return;
        }
        res.set_header("Content-Type", "application/json; charset=utf-8");
        res.status = 200;
        res.set_content(responseString, "application/json");
        curl_slist_free_all(headers);
        curl_easy_cleanup(curl);
    }
    catch (const std::exception& e) {
        sendError(res, std::string("Internal server error: ") + e.what(), 500);
    }
}

// 处理 SSE 流式响应：使用 cpp-httplib 的分块内容提供者和单独线程执行 curl 请求
void handleStreamResponse(httplib::Response& res, const std::string& token, const json& requestJson, bool isO1,
    bool hasImage) {
    try {
        // 对于 O1 模型采用普通响应逻辑
        if (isO1) {
            handleNormalResponse(res, token, requestJson, hasImage);
            return;
        }

        // 设置 SSE 必要的响应头
        res.set_header("Content-Type", "text/event-stream; charset=utf-8");
        res.set_header("Cache-Control", "no-cache");
        res.set_header("Connection", "keep-alive");

        // 创建共享缓冲区（使用 shared_ptr 便于在线程间管理生命周期）
        auto sharedBuffer = std::make_shared<SharedBuffer>();
        // 将共享缓冲区指针赋值给 thread_local 变量，以供不捕获 lambda 使用
        tls_sharedBuffer = sharedBuffer.get();

        // 设置 chunked 内容提供者（注意：lambda 不捕获任何变量，通过 thread_local 访问共享缓冲区）
        res.set_chunked_content_provider(
            "text/event-stream",
            [](size_t offset, httplib::DataSink& sink) -> bool {
                if (tls_sharedBuffer == nullptr) {
                    // 如果这里返回 false，表示当前 provider 已无法继续提供数据，结束
                    return false;
                }

                // 先拿到锁
                std::unique_lock<std::mutex> lock(tls_sharedBuffer->mtx);
                // 如果没有数据且还没结束，则等待一会儿
                if (tls_sharedBuffer->chunks.empty() && !tls_sharedBuffer->finished) {
                    tls_sharedBuffer->cv.wait_for(lock, std::chrono::milliseconds(100));
                }

                // 如果有待发送的数据
                if (!tls_sharedBuffer->chunks.empty()) {
                    std::string chunk = tls_sharedBuffer->chunks.front();
                    tls_sharedBuffer->chunks.pop();
                    lock.unlock(); // 写数据前就可以把锁释放
                    sink.write(chunk.c_str(), chunk.size());
                }

                // 如果已经结束且没有剩余数据，调用 sink.done() 并返回 false 表示不再回调
                if (tls_sharedBuffer->finished && tls_sharedBuffer->chunks.empty()) {
                    sink.done();
                    return false;
                }
                // 否则返回 true，让 httplib 继续回调本函数
                return true;
            },
            nullptr
        );


        // 启动一个独立线程执行 curl 请求，实时获取数据并写入 SharedBuffer
        std::thread curlThread([sharedBuffer, token, requestJson, hasImage]() {
            struct curl_slist* headers = getHeaderManager().getApiHeaders(
                token, HeaderManager::ApiType::CHAT_COMPLETIONS);
            if (hasImage) {
                headers = curl_slist_append(headers, "copilot-vision-request: true");
            }
            else {
                headers = curl_slist_append(headers, "copilot-vision-request: fasle");
            }
            std::string jsonBody = requestJson.dump();
            CURL* curl = curl_easy_init();
            if (!curl) {
                {
                    std::lock_guard<std::mutex> lock(sharedBuffer->mtx);
                    sharedBuffer->chunks.push("data: {\"error\": \"Failed to initialize CURL\"}\n\n");
                    sharedBuffer->finished = true;
                }
                sharedBuffer->cv.notify_one();
                return;
            }
            curl_easy_setopt(curl, CURLOPT_URL, "https://api.individual.githubcopilot.com/chat/completions");
            curl_easy_setopt(curl, CURLOPT_POST, 1L);
            curl_easy_setopt(curl, CURLOPT_CONNECTTIMEOUT, 60L);
            curl_easy_setopt(curl, CURLOPT_TIMEOUT, 60L);
            curl_easy_setopt(curl, CURLOPT_SSL_VERIFYPEER, 0L);
            curl_easy_setopt(curl, CURLOPT_SSL_VERIFYHOST, 0L);
            //curl_easy_setopt(curl, CURLOPT_PROXY, "127.0.0.1");
            //curl_easy_setopt(curl, CURLOPT_PROXYPORT, 5257L);
            curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);
            curl_easy_setopt(curl, CURLOPT_POSTFIELDS, jsonBody.c_str());
            curl_easy_setopt(curl, CURLOPT_BUFFERSIZE, 102400L);
            curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, StreamResponseCallback);
            curl_easy_setopt(curl, CURLOPT_WRITEDATA, sharedBuffer.get());

            CURLcode result = curl_easy_perform(curl);
            if (result != CURLE_OK) {
                std::lock_guard<std::mutex> lock(sharedBuffer->mtx);
                std::string errorMsg = "data: {\"error\": \"CURL error: ";
                errorMsg += curl_easy_strerror(result);
                errorMsg += "\"}\n\n";
                sharedBuffer->chunks.push(errorMsg);
                sharedBuffer->cv.notify_one();
            }
            long response_code;
            curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &response_code); {
                std::lock_guard<std::mutex> lock(sharedBuffer->mtx);
                sharedBuffer->finished = true;
            }
            sharedBuffer->cv.notify_one();
            curl_slist_free_all(headers);
            curl_easy_cleanup(curl);
            });
        curlThread.detach();
    }
    catch (const std::exception& e) {
        sendError(res, std::string("Error occurred while processing stream response: ") + e.what(), 500);
    }
}
