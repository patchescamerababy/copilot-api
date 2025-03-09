#include "global.h"
#include <nlohmann/json.hpp>
#include <httplib.h>

using json = nlohmann::json;

// ���ʹ�����Ӧ��ȫ�ֺ���ʵ��
void sendError(httplib::Response& res, const std::string& message, int HTTP_code) {
    res.status = HTTP_code;
    json error = {{"error", message}};
    res.set_content(error.dump(), "application/json");
}

// ��ȡ HeaderManager ����ʵ����ȫ�ֺ���ʵ��
HeaderManager& getHeaderManager() {
    return HeaderManager::getInstance();
}
