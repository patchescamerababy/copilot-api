#include "global.h"
#include <nlohmann/json.hpp>
#include <httplib.h>

using json = nlohmann::json;

// 发送错误响应的全局函数实现
void sendError(httplib::Response& res, const std::string& message, int HTTP_code) {
    res.status = HTTP_code;
    json error = {{"error", message}};
    res.set_content(error.dump(), "application/json");
}

// 获取 HeaderManager 单例实例的全局函数实现
HeaderManager& getHeaderManager() {
    return HeaderManager::getInstance();
}
