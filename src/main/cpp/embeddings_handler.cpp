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

// CURL �ص����������ڽ��� HTTP ��Ӧ����
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

// ���������� HTTP ����
CURL* createEmbeddingsConnection(struct curl_slist* headers, const std::string& jsonBody) {
    CURL* curl = curl_easy_init();
    if (!curl) {
        return nullptr;
    }
    curl_easy_setopt(curl, CURLOPT_BUFFERSIZE, 10240000L); // 10000KB

    // ���� URL
    curl_easy_setopt(curl, CURLOPT_URL, "https://api.individual.githubcopilot.com/embeddings");
    
    // ��ӡ����ͷ
    std::cout << "Embeddings Request Headers:" << std::endl;
    struct curl_slist* temp = headers;
    while (temp) {
        std::cout << temp->data << std::endl;
        temp = temp->next;
    }
    
    // �������󷽷��ͳ�ʱ
    curl_easy_setopt(curl, CURLOPT_POST, 1L);
    curl_easy_setopt(curl, CURLOPT_CONNECTTIMEOUT, 60L);
    curl_easy_setopt(curl, CURLOPT_TIMEOUT, 60L);
    
    // ���� SSL ֤����֤�Ա�ץ������
    curl_easy_setopt(curl, CURLOPT_SSL_VERIFYPEER, 0L);
    curl_easy_setopt(curl, CURLOPT_SSL_VERIFYHOST, 0L);
    // ���� HTTP ����������Ҫ��
   // curl_easy_setopt(curl, CURLOPT_PROXY, "127.0.0.1");
    //curl_easy_setopt(curl, CURLOPT_PROXYPORT, 5257L);

    // ��������ͷ
    curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);
    
    // ����������
    curl_easy_setopt(curl, CURLOPT_POSTFIELDS, jsonBody.c_str());
    
    return curl;
}

// ���� embeddings ����
void handleEmbeddings(const httplib::Request& req, httplib::Response& res) {
    // ���� CORS ͷ
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
        
        // ��ȡ���� token
        std::string longTermToken = auth.substr(7);
        
        // ��֤ token ǰ׺
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
        
        // ����������
        json requestJson;
        try {
            requestJson = json::parse(req.body);
        } catch (const std::exception& e) {
            sendError(res, std::string("Invalid JSON: ") + e.what(), 400);
            return;
        }
        
        // ��ȡ����ͷ
        struct curl_slist* headers = getHeaderManager().getApiHeaders(tempToken, HeaderManager::ApiType::EMBEDDINGS, requestJson);
        
        // ��������
        CURL* curl = createEmbeddingsConnection(headers, req.body);
        if (!curl) {
            sendError(res, "Failed to initialize CURL", 500);
            curl_slist_free_all(headers);
            return;
        }
        
        // ���ûص�����������Ӧ
        std::string responseString;
        curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, EmbeddingsResponseCallback);
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
        
        // ������Ӧͷ
        res.set_header("Content-Type", "application/json; charset=utf-8");
        
        if (response_code == 200) {
            // ���سɹ���Ӧ
            res.status = 200;
            res.set_content(responseString, "application/json");
            
            // �����־
            std::cout << "Embeddings request successful" << std::endl;
        } else {
            // ���ش�����Ӧ
            res.status = response_code;
            res.set_content(responseString, "application/json");
            
            // ���������־
            std::cerr << "Embeddings request failed with status code: " << response_code << std::endl;
            std::cerr << "Response: " << responseString << std::endl;
        }
        
        // ������Դ
        curl_slist_free_all(headers);
        curl_easy_cleanup(curl);
        
    } catch (const std::exception& e) {
        sendError(res, std::string("Internal server error: ") + e.what(), 500);
    }
}
