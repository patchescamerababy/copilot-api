#include <iostream>
#include <string>
#include <httplib.h>
#include <curl/curl.h>
#include <nlohmann/json.hpp>
#include "header_manager.h"

// ���� token_manager.cpp �еĺ��������� getValidTempToken ��������
extern std::string getValidTempToken(const std::string& longTermToken);
extern void sendError(httplib::Response& res, const std::string& message, int HTTP_code);

using json = nlohmann::json;

// �ص��������������ݲ�׷�ӵ� std::string ��
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

// ���������� CURL ���ӣ����������ⲿ /models API������ GET ����
CURL* createModelsConnection(struct curl_slist* headers) {
    CURL* curl = curl_easy_init();
    if (!curl) {
        return nullptr;
    }
    // ������ջ�����
    curl_easy_setopt(curl, CURLOPT_BUFFERSIZE, 10240000L);
    // �������� URL
    curl_easy_setopt(curl, CURLOPT_URL, "https://api.individual.githubcopilot.com/models");

    // �������ͷ�������ã�
    std::cout << "Models Request Headers:" << std::endl;
    for (struct curl_slist* h = headers; h; h = h->next) {
        std::cout << h->data << std::endl;
    }

    // ���� GET ����Ĭ�ϼ�Ϊ GET���������� POST ѡ�
    curl_easy_setopt(curl, CURLOPT_HTTPGET, 1L);
    curl_easy_setopt(curl, CURLOPT_CONNECTTIMEOUT, 60L);
    curl_easy_setopt(curl, CURLOPT_TIMEOUT, 60L);
    curl_easy_setopt(curl, CURLOPT_SSL_VERIFYPEER, 0L);
    curl_easy_setopt(curl, CURLOPT_SSL_VERIFYHOST, 0L);
    curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);

    return curl;
}

// ���� /v1/models ����GET����
// ��֤�����е� Authorization ͷ����ȡ��ʱ token����������ͷ������ GET ������ⲿ API�������Ӧ���ظ��ͻ���
void handleModels(const httplib::Request& req, httplib::Response& res) {
    // ������Ӧͷ���ο� Java ʵ�֣�
    res.set_header("Access-Control-Allow-Origin", "*");
    res.set_header("Access-Control-Allow-Credentials", "true");
    res.set_header("Access-Control-Allow-Methods", "GET, OPTIONS");
    res.set_header("Access-Control-Allow-Headers", "Content-Type, Authorization");
    res.set_header("Cache-Control", "no-cache");
    res.set_header("Content-Type", "application/json; charset=utf-8");
    res.set_header("Connection", "keep-alive");

    try {
        // ��֤ Authorization ͷ
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

        // �˴����� GET ����������������壬ֱ��ʹ�� token �����ⲿ API
        HeaderManager& hm = HeaderManager::getInstance();
        struct curl_slist* headers = hm.getApiHeaders(tempToken, HeaderManager::ApiType::MODELS, json{});

        // ���� CURL ���ӣ����� GET ����
        CURL* curl = createModelsConnection(headers);
        if (!curl) {
            sendError(res, "Failed to initialize CURL", 500);
            curl_slist_free_all(headers);
            return;
        }

        std::string responseString;
        curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, ResponseModelCallback);
        curl_easy_setopt(curl, CURLOPT_WRITEDATA, &responseString);

        // ִ�� CURL ����
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
