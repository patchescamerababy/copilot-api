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

// �ⲿ��������
extern std::string getValidTempToken(const std::string& longTermToken);
extern void sendError(httplib::Response& res, const std::string& message, int HTTP_code);
extern HeaderManager& getHeaderManager();

using json = nlohmann::json;

// ���� SSE ��ʽ��Ӧ�б�����������ݵ��̰߳�ȫ��������
struct SharedBuffer {
    std::queue<std::string> chunks;
    std::mutex mtx;
    std::condition_variable cv;
    bool finished = false;
};

// ��̬ thread_local �����������ڲ������ lambda �д��� SharedBuffer ָ��
static thread_local SharedBuffer* tls_sharedBuffer = nullptr;

// ��ͨ��Ӧ�Ļص������������յ������������ۼӵ� responseString ��
size_t ResponseCallback(void* contents, size_t size, size_t nmemb, std::string* s) {
    size_t newLength = size * nmemb;
    try {
        s->reserve(s->size() + newLength);
        s->append(static_cast<char*>(contents), newLength);
        return newLength;
    } catch (std::bad_alloc& e) {
        return 0;
    }
}

// ��ʽ�ص��������� curl ���ã���
// �����յ�������׷�ӵ��ֲ߳̾����ۼƻ��������������з�ʱ�����У���װΪ SSE ��ʽ��д�� SharedBuffer
size_t StreamResponseCallback(void* contents, size_t size, size_t nmemb, void* userp) {
    size_t realsize = size * nmemb;
    SharedBuffer* sharedBuffer = static_cast<SharedBuffer*>(userp);
    // ʹ���ֲ߳̾������洢�ۼ�����
    static thread_local std::string accumulated;
    std::string data(static_cast<char*>(contents), realsize);
    accumulated.append(data);
    size_t pos = 0;
    // ÿ����һ�����з�����Ϊ���յ�һ����������
    while ((pos = accumulated.find("data: ")) != std::string::npos) {
        std::string line = accumulated.substr(0, pos + 1);
        {
            std::lock_guard<std::mutex> lock(sharedBuffer->mtx);
            sharedBuffer->chunks.push(line);
        }
        sharedBuffer->cv.notify_one();
        accumulated.erase(0, pos + 1);
    }
    return realsize;
}

// ��ȡ Copilot API ������ͷ
struct curl_slist* getCopilotHeaders(const std::string& token, bool hasImage = false) {
    struct curl_slist* headers = nullptr;
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
    headers = curl_slist_append(headers, "openai-intent: conversation-panel");

    if (hasImage) {
        headers = curl_slist_append(headers, "copilot-vision-request: true");
    } else {
        headers = curl_slist_append(headers, "copilot-vision-request: false");
    }

    std::string authHeader = "Authorization: Bearer " + token;
    headers = curl_slist_append(headers, authHeader.c_str());

    return headers;
}

// ������������ͨ����� CURL ���ӣ�����ʽ��
CURL* createConnection(struct curl_slist* headers, const std::string& jsonBody) {
    CURL* curl = curl_easy_init();
    if (!curl) {
        return nullptr;
    }
    curl_easy_setopt(curl, CURLOPT_BUFFERSIZE, 102400L);
    std::cout << "Request JSON: " << jsonBody << std::endl;
    std::cout << "Request Headers:" << std::endl;
    for (struct curl_slist* temp = headers; temp; temp = temp->next) {
        std::cout << temp->data << std::endl;
    }
    curl_easy_setopt(curl, CURLOPT_URL, "https://api.individual.githubcopilot.com/chat/completions");
    curl_easy_setopt(curl, CURLOPT_POST, 1L);
    curl_easy_setopt(curl, CURLOPT_CONNECTTIMEOUT, 60L);
    curl_easy_setopt(curl, CURLOPT_TIMEOUT, 60L);
    curl_easy_setopt(curl, CURLOPT_SSL_VERIFYPEER, 0L);
    curl_easy_setopt(curl, CURLOPT_SSL_VERIFYHOST, 0L);
    curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);
    curl_easy_setopt(curl, CURLOPT_POSTFIELDS, jsonBody.c_str());
    return curl;
}

// ������ͨ������ʽ����Ӧ
void handleNormalResponse(httplib::Response& res, const std::string& token, const json& requestJson) {
    try {
        struct curl_slist* headers = getHeaderManager().getApiHeaders(token, HeaderManager::ApiType::CHAT_COMPLETIONS, requestJson);
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
    } catch (const std::exception& e) {
        sendError(res, std::string("Internal server error: ") + e.what(), 500);
    }
}

// ���� SSE ��ʽ��Ӧ��ʹ�� cpp-httplib �ķֿ������ṩ�ߺ͵����߳�ִ�� curl ����
void handleStreamResponse(httplib::Response& res, const std::string& token, const json& requestJson, bool isO1) {
    try {
        // ���� O1 ģ�Ͳ�����ͨ��Ӧ�߼�
        if (isO1) {
            handleNormalResponse(res, token, requestJson);
            return;
        }

        // ���� SSE ��Ҫ����Ӧͷ
        res.set_header("Content-Type", "text/event-stream; charset=utf-8");
        res.set_header("Cache-Control", "no-cache");
        res.set_header("Connection", "keep-alive");

        // ��������������ʹ�� shared_ptr �������̼߳�����������ڣ�
        auto sharedBuffer = std::make_shared<SharedBuffer>();
        // ����������ָ�븳ֵ�� thread_local �������Թ������� lambda ʹ��
        tls_sharedBuffer = sharedBuffer.get();

        // ���� chunked �����ṩ�ߣ�ע�⣺lambda �������κα�����ͨ�� thread_local ���ʹ���������
        res.set_chunked_content_provider(
            "text/event-stream",
            [](size_t offset, httplib::DataSink &sink) -> bool {
                if (tls_sharedBuffer == nullptr) {
                    // ������ﷵ�� false����ʾ��ǰ provider ���޷������ṩ���ݣ�����
                    return false;
                }

                // ���õ���
                std::unique_lock<std::mutex> lock(tls_sharedBuffer->mtx);
                // ���û�������һ�û��������ȴ�һ���
                if (tls_sharedBuffer->chunks.empty() && !tls_sharedBuffer->finished) {
                    tls_sharedBuffer->cv.wait_for(lock, std::chrono::milliseconds(100));
                }

                // ����д����͵�����
                if (!tls_sharedBuffer->chunks.empty()) {
                    std::string chunk = tls_sharedBuffer->chunks.front();
                    tls_sharedBuffer->chunks.pop();
                    lock.unlock();  // д����ǰ�Ϳ��԰����ͷ�
                    sink.write(chunk.c_str(), chunk.size());
                }

                // ����Ѿ�������û��ʣ�����ݣ����� sink.done() ������ false ��ʾ���ٻص�
                if (tls_sharedBuffer->finished && tls_sharedBuffer->chunks.empty()) {
                    sink.done();
                    return false;
                }
                // ���򷵻� true���� httplib �����ص�������
                return true;
            },
            nullptr
        );


        // ����һ�������߳�ִ�� curl ����ʵʱ��ȡ���ݲ�д�� SharedBuffer
        std::thread curlThread([sharedBuffer, token, requestJson]() {
            struct curl_slist* headers = getHeaderManager().getApiHeaders(token, HeaderManager::ApiType::CHAT_COMPLETIONS, requestJson);
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
            curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &response_code);
            {
                std::lock_guard<std::mutex> lock(sharedBuffer->mtx);
                sharedBuffer->finished = true;
            }
            sharedBuffer->cv.notify_one();
            curl_slist_free_all(headers);
            curl_easy_cleanup(curl);
        });
        curlThread.detach();
    } catch (const std::exception& e) {
        sendError(res, std::string("Error occurred while processing stream response: ") + e.what(), 500);
    }
}
