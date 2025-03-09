#ifndef TOKEN_MANAGER_H
#define TOKEN_MANAGER_H

#include <string>

// 声明获取有效临时 Token 的函数
std::string getValidTempToken(const std::string &longTermToken);

#endif // TOKEN_MANAGER_H
