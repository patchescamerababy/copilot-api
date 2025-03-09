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

// ���� token_manager.cpp �еĺ�������
extern std::string getValidTempToken(const std::string& longTermToken);
extern void sendError(httplib::Response& res, const std::string& message, int HTTP_code);
extern HeaderManager& getHeaderManager();

using json = nlohmann::json;
// CURL �ص����������ڽ��� HTTP ��Ӧ���ݣ�����ʽ��Ӧ���ã�
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

// ��ʽ�ص������ģ������� accumulated ��Ա�����ۼ�����
struct StreamContext {
    httplib::Response* res;
    bool isO1;
    std::string model;
    std::string accumulated;  // �����ۼƽ��յ�������
};

// �޸ĺ����ʽ�ص���������ÿ������׷�ӵ� accumulated ��
size_t StreamResponseCallback(void* contents, size_t size, size_t nmemb, void* userp) {
    size_t realsize = size * nmemb;
    StreamContext* context = static_cast<StreamContext*>(userp);

    // ����ǰ���յ�������׷�ӵ� accumulated ��
    context->accumulated.append(static_cast<char*>(contents), realsize);

    // �˴����ٵ��� res.set_content()�����⸲��ǰ�������
    return realsize;
}


// ��ȡ Copilot API ������ͷ
struct curl_slist* getCopilotHeaders(const std::string& token, bool hasImage = false) {
    struct curl_slist* headers = nullptr;
    // ���û�������ͷ
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

    // ������������ͷ
    headers = curl_slist_append(headers, "openai-intent: conversation-panel");

    // ����ͼ������ͷ
    if (hasImage) {
        headers = curl_slist_append(headers, "copilot-vision-request: true");
    } else {
        headers = curl_slist_append(headers, "copilot-vision-request: false");
    }

    // ������Ȩͷ
    std::string authHeader = "Authorization: Bearer " + token;
    headers = curl_slist_append(headers, authHeader.c_str());

    return headers;
}

// ���������� HTTP ����
CURL* createConnection(struct curl_slist* headers, const std::string& jsonBody) {
    CURL* curl = curl_easy_init();
    if (!curl) {
        return nullptr;
    }

    // ���� libcurl ���ջ�������С
    curl_easy_setopt(curl, CURLOPT_BUFFERSIZE, 102400L); // �趨Ϊ 100KB

    // ��ӡ���� JSON
    std::cout << "Request JSON: " << jsonBody << std::endl;

    // ��ӡ����ͷ
    std::cout << "Request Headers:" << std::endl;
    struct curl_slist* temp = headers;
    while (temp) {
        std::cout << temp->data << std::endl;
        temp = temp->next;
    }

    // ���� URL
    curl_easy_setopt(curl, CURLOPT_URL, "https://api.individual.githubcopilot.com/chat/completions");

    // �������󷽷��ͳ�ʱ
    curl_easy_setopt(curl, CURLOPT_POST, 1L);
    curl_easy_setopt(curl, CURLOPT_CONNECTTIMEOUT, 60L);
    curl_easy_setopt(curl, CURLOPT_TIMEOUT, 60L);

    // ���� SSL ֤����֤������ץ��������
    curl_easy_setopt(curl, CURLOPT_SSL_VERIFYPEER, 0L);
    curl_easy_setopt(curl, CURLOPT_SSL_VERIFYHOST, 0L);
    // ���� HTTP ����������Ҫ��
    //curl_easy_setopt(curl, CURLOPT_PROXY, "127.0.0.1");
    //curl_easy_setopt(curl, CURLOPT_PROXYPORT, 5257L);

    // ��������ͷ
    curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);

    // ����������
    curl_easy_setopt(curl, CURLOPT_POSTFIELDS, jsonBody.c_str());

    return curl;
}



// ������ͨ��Ӧ
void handleNormalResponse(httplib::Response& res, const std::string& token, const json& requestJson) {
    try {
        // ��ȡ����ͷ
        struct curl_slist* headers = getHeaderManager().getApiHeaders(token, HeaderManager::ApiType::CHAT_COMPLETIONS, requestJson);

        // �������ӣ�ע�����ｫ dump ��� JSON �洢�ھֲ������У�ȷ���ڴ���Ч
        std::string jsonBody = requestJson.dump();
        CURL* curl = createConnection(headers, jsonBody);
        if (!curl) {
            sendError(res, "Failed to initialize CURL", 500);
            curl_slist_free_all(headers);
            return;
        }

        // ���ûص�����������Ӧ
        std::string responseString;
        curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, ResponseCallback);
        curl_easy_setopt(curl, CURLOPT_WRITEDATA, &responseString);

        // ִ������
        CURLcode result = curl_easy_perform(curl);

        // ��������Ƿ�ɹ�
        if (result != CURLE_OK) {
            sendError(res, std::string("CURL error: ") + curl_easy_strerror(result), 500);
            curl_slist_free_all(headers);
            curl_easy_cleanup(curl);
            return;
        }

        // ��ȡ HTTP ״̬��
        long response_code;
        curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &response_code);

        if (response_code != 200) {
            sendError(res, responseString, response_code);
            curl_slist_free_all(headers);
            curl_easy_cleanup(curl);
            return;
        }

        // ������Ӧͷ����Ӧ����
        res.set_header("Content-Type", "application/json; charset=utf-8");
        res.status = 200;
        res.set_content(responseString, "application/json");

        // ������Դ
        curl_slist_free_all(headers);
        curl_easy_cleanup(curl);
    } catch (const std::exception& e) {
        sendError(res, std::string("Internal server error: ") + e.what(), 500);
    }
}

// ������ʽ��Ӧ

// �޸ĺ�� handleStreamResponse ����
void handleStreamResponse(httplib::Response& res, const std::string& token, const json& requestJson, bool isO1) {
    try {
        // ��ȡ����ͷ
        struct curl_slist* headers = getHeaderManager().getApiHeaders(token, HeaderManager::ApiType::CHAT_COMPLETIONS, requestJson);

        // �������ӣ�ȷ�� requestJson.dump() �����ھֲ�������
        std::string jsonBody = requestJson.dump();
        CURL* curl = curl_easy_init();
        if (!curl) {
            sendError(res, "Failed to initialize CURL", 500);
            curl_slist_free_all(headers);
            return;
        }

        // ���� URL�����󷽷�����ʱ������Ȳ������ɲο�ԭ���룩
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
        // ��ѡ�����󻺳���
        curl_easy_setopt(curl, CURLOPT_BUFFERSIZE, 102400L);

        // ����� O1 ģ�ͣ�ֱ�ӵ�����ͨ��Ӧ����
        if (isO1) {
            // ������ͨ��Ӧ�����ú����ڲ�ҲӦ�ۼ����ݻ�ֱ�ӷ���������Ӧ��
            // ����ֻ��ʾ��������ֱ��������Դ�󷵻�
            curl_slist_free_all(headers);
            curl_easy_cleanup(curl);
            handleStreamResponse(res, token, requestJson, false);  // ����� handleNormalResponse()
            return;
        }

        // ������ʽ��Ӧͷ
        res.set_header("Content-Type", "text/event-stream; charset=utf-8");
        res.set_header("Cache-Control", "no-cache");
        res.set_header("Connection", "keep-alive");

        // ��ʼ����ʽ�ص������ģ�������ۼƻ�����
        StreamContext context;
        context.res = &res;
        context.isO1 = isO1;
        context.model = requestJson.value("model", "gpt-4");
        context.accumulated = "";

        // ���ûص����������ݴ洢������
        curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, StreamResponseCallback);
        curl_easy_setopt(curl, CURLOPT_WRITEDATA, &context);

        // ִ�������ڼ�ص������᲻��׷�����ݵ� context.accumulated ��
        CURLcode result = curl_easy_perform(curl);
        if (result != CURLE_OK) {
            sendError(res, std::string("CURL error: ") + curl_easy_strerror(result), 500);
            curl_slist_free_all(headers);
            curl_easy_cleanup(curl);
            return;
        }

        // ��ȡ HTTP ״̬��
        long response_code;
        curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &response_code);
        if (response_code != 200) {
            sendError(res, "Error occurred in streaming response", response_code);
        } else {
            // ���ۼƵ�����һ���Է��ظ��ͻ���
            res.set_content(context.accumulated, "text/event-stream");
        }

        // ������Դ
        curl_slist_free_all(headers);
        curl_easy_cleanup(curl);
    } catch (const std::exception& e) {
        sendError(res, std::string("Error occurred while processing stream response: ") + e.what(), 500);
    }
}