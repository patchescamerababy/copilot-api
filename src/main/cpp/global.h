#ifndef GLOBAL_H
#define GLOBAL_H

#include "header_manager.h"
#include <string>
#include <httplib.h>

// ��ȡ HeaderManager ����ʵ����ȫ�ֺ���
HeaderManager& getHeaderManager();

// ���ʹ�����Ӧ��ȫ�ֺ���
void sendError(httplib::Response& res, const std::string& message, int HTTP_code);

// Base64 ���뺯������
std::string base64_encode(const unsigned char* bytes_to_encode, unsigned int in_len);

// ����ͼƬ��ת��Ϊ Base64 �ĺ�������
std::string downloadImageAsBase64(const std::string& url);

#endif // GLOBAL_H
