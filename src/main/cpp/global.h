#ifndef GLOBAL_H
#define GLOBAL_H

#include "header_manager.h"
#include <string>
#include <httplib.h>

// 获取 HeaderManager 单例实例的全局函数
HeaderManager& getHeaderManager();

// 发送错误响应的全局函数
void sendError(httplib::Response& res, const std::string& message, int HTTP_code);

// Base64 编码函数声明
std::string base64_encode(const unsigned char* bytes_to_encode, unsigned int in_len);

// 下载图片并转换为 Base64 的函数声明
std::string downloadImageAsBase64(const std::string& url);

#endif // GLOBAL_H
