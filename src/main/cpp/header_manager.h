#ifndef HEADER_MANAGER_H
#define HEADER_MANAGER_H

#include <string>
#include <curl/curl.h>
#include <nlohmann/json.hpp>

using json = nlohmann::json;

// ����ͷ�������࣬����Ϊ��ͬ API �˵������ʵ�������ͷ
class HeaderManager {
public:
    // ��������ö��
    enum class ApiType {
        CHAT_COMPLETIONS,
        EMBEDDINGS,
        MODELS,
        TOKEN
    };

    HeaderManager() = default;
    ~HeaderManager() = default;

    // ��ȡ����ʵ��
    static HeaderManager& getInstance() {
        static HeaderManager instance;
        return instance;
    }

    // ��ȡ API ����ͷ
    struct curl_slist* getApiHeaders(const std::string& token, ApiType apiType);

    // ��ȡ Token API ����ͷ
    struct curl_slist* getTokenHeaders(const std::string& longTermToken);

private:
    // �������ʮ�������ַ���
    std::string generateRandomHex(int length);

    // �������UUID��ʽ�ַ���
    std::string generateRandomXRequestId(int length);

    // ��ȡ�ỰID
    std::string getSessionId();

    // �������UUID
    std::string generateRandomUuid();

    // ��ȡ����ID
    std::string getMachineId();
    // ��ӻ�������ͷ
    void addBasicHeaders(struct curl_slist** headers);

    // ��Ӱ�ȫ�������ͷ
    void addSecurityHeaders(struct curl_slist** headers);

    // �����Ȩͷ
    void addAuthorizationHeader(struct curl_slist** headers, const std::string& token, bool isLongTermToken = false);

    // ��������Ƿ����ͼ��
    bool hasImageContent(const json& requestJson);
};

#endif // HEADER_MANAGER_H
