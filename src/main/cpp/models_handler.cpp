// model_handler.cpp
#include <iostream>
#include <string>
#include <httplib.h>
#include <curl/curl.h>
#include <nlohmann/json.hpp>
#include "header_manager.h"

// ���� token_manager.cpp �еĺ�������
extern std::string getValidTempToken(const std::string& longTermToken);
extern void sendError(httplib::Response& res, const std::string& message, int HTTP_code);
extern HeaderManager& getHeaderManager();

using json = nlohmann::json;

// �� completion_handler ��һ�µĻص��������������ݲ�׷�ӵ� std::string ��
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

// ���������� HTTP ����
CURL* createModelsConnection(struct curl_slist* headers) {
    CURL* curl = curl_easy_init();
    if (!curl) {
        return nullptr;
    }
    // ���� libcurl ���ջ�������С���˴��趨Ϊ 100KB��
    curl_easy_setopt(curl, CURLOPT_BUFFERSIZE, 10240000L);
    // �������� URL
    curl_easy_setopt(curl, CURLOPT_URL, "https://api.individual.githubcopilot.com/models");

    // ��ӡ����ͷ�����ڵ��ԣ�
    std::cout << "Models Request Headers:" << std::endl;
    struct curl_slist* temp = headers;
    while (temp) {
        std::cout << temp->data << std::endl;
        temp = temp->next;
    }

    // ���� POST ����ͳ�ʱ����
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

    return curl;
}

// ���� models �����������
void handleModels(const httplib::Request& req, httplib::Response& res) {
    // ���� CORS ��������ص���Ӧͷ
    res.set_header("Access-Control-Allow-Origin", "*");
    res.set_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    res.set_header("Access-Control-Allow-Headers", "Content-Type, Authorization");
    res.set_header("Connection", "keep-alive");

    try {
        // ��֤ Authorization ͷ
        std::string auth = req.get_header_value("Authorization");
        if (auth.empty() || auth.substr(0, 7) != "Bearer ") {
            sendError(res, "Token is invalid.", 401);
            return;
        }
        // ��ȡ���� token������֤ token ǰ׺
        std::string longTermToken = auth.substr(7);
        if (!(longTermToken.substr(0, 3) == "ghu" || longTermToken.substr(0, 3) == "gho")) {
            sendError(res, "Invalid token prefix.", 401);
            return;
        }
        // ��ȡ��Ч����ʱ token
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

        // ��ȡ����ͷ���˴����� HeaderManager �е� getApiHeaders ������
        struct curl_slist* headers = getHeaderManager().getApiHeaders(tempToken, HeaderManager::ApiType::MODELS);

        // ��������
        CURL* curl = createModelsConnection(headers);
        if (!curl) {
            sendError(res, "Failed to initialize CURL", 500);
            curl_slist_free_all(headers);
            return;
        }

        // ���ûص�������ʹ���� completion_handler ��һ�µ� ResponseCallback
        std::string responseString;
        curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, ResponseModelCallback);
        curl_easy_setopt(curl, CURLOPT_WRITEDATA, &responseString);

        // ��� models �ӿ�Ҫ�������壬��ȷ�����ã����磺curl_easy_setopt(curl, CURLOPT_POSTFIELDS, "{}");��
        // curl_easy_setopt(curl, CURLOPT_POSTFIELDS, "{}");

        // ִ������
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

        // ������Ӧͷ������ JSON ��ʽ����
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

        // ������Դ
        curl_slist_free_all(headers);
        curl_easy_cleanup(curl);
    } catch (const std::exception& e) {
        sendError(res, std::string("Internal server error: ") + e.what(), 500);
    }
}
