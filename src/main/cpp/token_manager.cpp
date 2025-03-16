#include <cstdlib>
#include <iostream>
#include <string>
#include <vector>
#include <random>
#include <chrono>
#include <mutex>
#include <sqlite3.h>
#include <curl/curl.h>
#include <nlohmann/json.hpp>
#include <httplib.h>
#include "header_manager.h"
#include "token_manager.h"

using json = nlohmann::json;

// 全局互斥锁，用于 token 操作的线程安全
std::mutex token_mutex;

// CURL 回调函数，用于接收 HTTP 响应数据
size_t WriteCallback(void* contents, size_t size, size_t nmemb, std::string* s) {
    size_t newLength = size * nmemb;
    try {
        s->append((char*)contents, newLength);
        return newLength;
    }
    catch (std::bad_alloc& e) {
        return 0;
    }
}

class TokenManager {
private:
    sqlite3* db;

    // 连接到 SQLite 数据库
    sqlite3* connect() {
        sqlite3* conn = nullptr;
        int rc = sqlite3_open("tokens.db", &conn);
        if (rc) {
            std::cerr << "Connection error: " << sqlite3_errmsg(conn) << std::endl;
            return nullptr;
        }

        // 启用外键约束
        char* errMsg = nullptr;
        rc = sqlite3_exec(conn, "PRAGMA foreign_keys = ON;", nullptr, nullptr, &errMsg);
        if (rc != SQLITE_OK) {
            std::cerr << "SQL error: " << errMsg << std::endl;
            sqlite3_free(errMsg);
        }

        return conn;
    }

public:
    TokenManager() : db(nullptr) {
        // 创建数据库连接
        db = connect();
        if (!db) {
            std::cerr << "Failed to connect to database" << std::endl;
            return;
        }

        // 创建 tokens 表，添加 username 字段
        const char* createTableSQL =
            "CREATE TABLE IF NOT EXISTS tokens ("
            "id INTEGER PRIMARY KEY AUTOINCREMENT, "
            "long_term_token TEXT NOT NULL UNIQUE, "
            "temp_token TEXT, "
            "temp_token_expiry INTEGER, "
            "username TEXT"
            ");";

        char* errMsg = nullptr;
        int rc = sqlite3_exec(db, createTableSQL, nullptr, nullptr, &errMsg);
        if (rc != SQLITE_OK) {
            std::cerr << "SQL error: " << errMsg << std::endl;
            sqlite3_free(errMsg);
        }
    }

    ~TokenManager() {
        if (db) {
            sqlite3_close(db);
            db = nullptr;
        }
    }

    // 检查长期 token 是否存在
    bool isLongTermTokenExists(const std::string& longTermToken) {
        if (!db) return false;

        sqlite3_stmt* stmt = nullptr;
        const char* query = "SELECT id FROM tokens WHERE long_term_token = ?";
        int rc = sqlite3_prepare_v2(db, query, -1, &stmt, nullptr);

        if (rc != SQLITE_OK) {
            std::cerr << "Error preparing statement: " << sqlite3_errmsg(db) << std::endl;
            return false;
        }

        sqlite3_bind_text(stmt, 1, longTermToken.c_str(), -1, SQLITE_STATIC);

        bool exists = false;
        if (sqlite3_step(stmt) == SQLITE_ROW) {
            exists = true;
        }

        sqlite3_finalize(stmt);
        return exists;
    }

    // 获取用户名
    std::string getUsername(const std::string& longTermToken) {
        if (!db) return "";

        sqlite3_stmt* stmt = nullptr;
        const char* query = "SELECT username FROM tokens WHERE long_term_token = ?";
        int rc = sqlite3_prepare_v2(db, query, -1, &stmt, nullptr);

        if (rc != SQLITE_OK) {
            std::cerr << "Error preparing statement: " << sqlite3_errmsg(db) << std::endl;
            return "";
        }

        sqlite3_bind_text(stmt, 1, longTermToken.c_str(), -1, SQLITE_STATIC);

        std::string username = "";
        if (sqlite3_step(stmt) == SQLITE_ROW) {
            const char* name = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 0));
            if (name) {
                username = name;
            }
        }

        sqlite3_finalize(stmt);
        return username;
    }

    // 添加新的长期 token 记录，包含用户名
    bool addLongTermToken(const std::string& longTermToken, const std::string& tempToken, long tempTokenExpiry, const std::string& username = "") {
        if (!db) return false;

        sqlite3_stmt* stmt = nullptr;
        const char* insertSQL = "INSERT INTO tokens(long_term_token, temp_token, temp_token_expiry, username) VALUES(?, ?, ?, ?)";
        int rc = sqlite3_prepare_v2(db, insertSQL, -1, &stmt, nullptr);

        if (rc != SQLITE_OK) {
            std::cerr << "Error preparing statement: " << sqlite3_errmsg(db) << std::endl;
            return false;
        }

        sqlite3_bind_text(stmt, 1, longTermToken.c_str(), -1, SQLITE_STATIC);
        sqlite3_bind_text(stmt, 2, tempToken.c_str(), -1, SQLITE_STATIC);
        sqlite3_bind_int64(stmt, 3, tempTokenExpiry);
        sqlite3_bind_text(stmt, 4, username.c_str(), -1, SQLITE_STATIC);

        rc = sqlite3_step(stmt);
        sqlite3_finalize(stmt);

        if (rc != SQLITE_DONE) {
            std::cerr << "Error adding long-term token: " << sqlite3_errmsg(db) << std::endl;
            return false;
        }

        std::cout << "Long-term token added successfully." << std::endl;
        return true;
    }

    // 获取随机长期 token
    std::string getRandomLongTermToken() {
        if (!db) return "";

        sqlite3_stmt* stmt = nullptr;
        const char* query = "SELECT long_term_token FROM tokens";
        int rc = sqlite3_prepare_v2(db, query, -1, &stmt, nullptr);

        if (rc != SQLITE_OK) {
            std::cerr << "Error preparing statement: " << sqlite3_errmsg(db) << std::endl;
            return "";
        }

        std::vector<std::string> tokens;
        while (sqlite3_step(stmt) == SQLITE_ROW) {
            const char* token = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 0));
            if (token) {
                tokens.push_back(token);
            }
        }

        sqlite3_finalize(stmt);

        if (tokens.empty()) {
            std::cout << "No tokens found in the database." << std::endl;
            return "";
        }

        // 随机选择一个 token
        std::random_device rd;
        std::mt19937 gen(rd());
        std::uniform_int_distribution<> distrib(0, tokens.size() - 1);
        return tokens[distrib(gen)];
    }

    // 获取临时 token
    std::string getTempToken(const std::string& longTermToken) {
        if (!db) return "";

        sqlite3_stmt* stmt = nullptr;
        const char* query = "SELECT temp_token FROM tokens WHERE long_term_token = ?";
        int rc = sqlite3_prepare_v2(db, query, -1, &stmt, nullptr);

        if (rc != SQLITE_OK) {
            std::cerr << "Error preparing statement: " << sqlite3_errmsg(db) << std::endl;
            return "";
        }

        sqlite3_bind_text(stmt, 1, longTermToken.c_str(), -1, SQLITE_STATIC);

        std::string tempToken = "";
        if (sqlite3_step(stmt) == SQLITE_ROW) {
            const char* token = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 0));
            if (token) {
                tempToken = token;
            }
        }

        sqlite3_finalize(stmt);
        return tempToken;
    }

    // 更新临时 token 及其过期时间
    bool updateTempToken(const std::string& longTermToken, const std::string& newTempToken, long newExpiry) {
        if (!db) return false;

        sqlite3_stmt* stmt = nullptr;
        const char* updateSQL = "UPDATE tokens SET temp_token = ?, temp_token_expiry = ? WHERE long_term_token = ?";
        int rc = sqlite3_prepare_v2(db, updateSQL, -1, &stmt, nullptr);

        if (rc != SQLITE_OK) {
            std::cerr << "Error preparing statement: " << sqlite3_errmsg(db) << std::endl;
            return false;
        }

        sqlite3_bind_text(stmt, 1, newTempToken.c_str(), -1, SQLITE_STATIC);
        sqlite3_bind_int64(stmt, 2, newExpiry);
        sqlite3_bind_text(stmt, 3, longTermToken.c_str(), -1, SQLITE_STATIC);

        rc = sqlite3_step(stmt);
        sqlite3_finalize(stmt);

        if (rc != SQLITE_DONE) {
            std::cerr << "Error updating temp_token: " << sqlite3_errmsg(db) << std::endl;
            return false;
        }

        std::cout << "Temp token updated successfully." << std::endl;
        return true;
    }

    // 更新用户名
    bool updateUsername(const std::string& longTermToken, const std::string& username) {
        if (!db) return false;

        sqlite3_stmt* stmt = nullptr;
        const char* updateSQL = "UPDATE tokens SET username = ? WHERE long_term_token = ?";
        int rc = sqlite3_prepare_v2(db, updateSQL, -1, &stmt, nullptr);

        if (rc != SQLITE_OK) {
            std::cerr << "Error preparing statement: " << sqlite3_errmsg(db) << std::endl;
            return false;
        }

        sqlite3_bind_text(stmt, 1, username.c_str(), -1, SQLITE_STATIC);
        sqlite3_bind_text(stmt, 2, longTermToken.c_str(), -1, SQLITE_STATIC);

        rc = sqlite3_step(stmt);
        sqlite3_finalize(stmt);

        if (rc != SQLITE_DONE) {
            std::cerr << "Error updating username: " << sqlite3_errmsg(db) << std::endl;
            return false;
        }

        std::cout << "Username updated successfully." << std::endl;
        return true;
    }
};

// 从 token 中提取时间戳
int extractTimestamp(const std::string& token) {
    size_t pos = 0;
    std::string delimiter = ";";
    std::string tokenCopy = token;

    while ((pos = tokenCopy.find(delimiter)) != std::string::npos) {
        std::string part = tokenCopy.substr(0, pos);
        if (part.substr(0, 4) == "exp=") {
            return std::stoi(part.substr(4));
        }
        tokenCopy.erase(0, pos + delimiter.length());
    }

    // 检查最后一部分
    if (tokenCopy.substr(0, 4) == "exp=") {
        return std::stoi(tokenCopy.substr(4));
    }

    return 0;
}

// 检查 token 是否过期
bool isTokenExpired(const std::string& token) {
    int exp = extractTimestamp(token);
    int currentEpoch = std::chrono::duration_cast<std::chrono::seconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();

    std::cout << "\n   Current epoch: " << currentEpoch << std::endl;
    std::cout << "Expiration epoch: " << exp << std::endl;

    // 计算剩余时间
    int remainingSeconds = exp - currentEpoch;
    int minutes = remainingSeconds / 60;
    int seconds = remainingSeconds % 60;

    std::cout << "Remaining: " << minutes << " minutes " << seconds << " seconds" << std::endl;

    return exp < currentEpoch;
}

// 从 GitHub API 获取用户名
std::string fetchUsername(const std::string& longTermToken) {
    CURL* curl = curl_easy_init();
    std::string responseString;

    if (curl) {
        // 设置 URL
        curl_easy_setopt(curl, CURLOPT_URL, "https://api.github.com/user");

        // 设置请求头
        struct curl_slist* headers = NULL;
        headers = curl_slist_append(headers, "Host: api.github.com");
        headers = curl_slist_append(headers, "Connection: keep-alive");
        headers = curl_slist_append(headers, "accept: application/vnd.github+json");

        std::string authHeader = "authorization: Bearer " + longTermToken;
        headers = curl_slist_append(headers, authHeader.c_str());

        headers = curl_slist_append(headers, "user-agent: GitHubCopilotChat/0.23.2");
        headers = curl_slist_append(headers, "x-github-api-version: 2022-11-28");
        headers = curl_slist_append(headers, "Sec-Fetch-Site: none");
        headers = curl_slist_append(headers, "Sec-Fetch-Mode: no-cors");
        headers = curl_slist_append(headers, "Sec-Fetch-Dest: empty");
        //headers = curl_slist_append(headers, "Accept-Encoding: gzip, deflate, br, zstd");

        curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);

        // 设置回调函数接收响应
        curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, WriteCallback);
        curl_easy_setopt(curl, CURLOPT_WRITEDATA, &responseString);

        // 执行请求
        CURLcode res = curl_easy_perform(curl);

        // 检查请求是否成功
        if (res != CURLE_OK) {
            std::cerr << "curl_easy_perform() failed: " << curl_easy_strerror(res) << std::endl;
        }
        else {
            long response_code;
            curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &response_code);

            if (response_code == 200) {
                try {
                    json jsonObject = json::parse(responseString);
                    if (jsonObject.contains("login")) {
                        std::string login = jsonObject["login"];
                        std::cout << "Fetched username: " << login << std::endl;
                        curl_slist_free_all(headers);
                        curl_easy_cleanup(curl);
                        return login;
                    }
                    else {
                        std::cout << "\"login\" field not found in the response." << std::endl;
                    }
                }
                catch (const std::exception& e) {
                    std::cerr << "JSON parsing error: " << e.what() << std::endl;
                }
            }
            else {
                std::cout << "Request failed, status code: " << response_code << std::endl;
                std::cout << "Response body: " << responseString << std::endl;
            }
        }

        // 清理
        curl_slist_free_all(headers);
        curl_easy_cleanup(curl);
    }

    return "";
}

// 获取新的临时 token
// 注意：HeaderManager 实例已在 completion_handler.cpp 中定义
extern HeaderManager& getHeaderManager();

std::string GetToken(const std::string& longTermToken) {
    CURL* curl = curl_easy_init();
    std::string responseString;

    if (curl) {
        // 设置 URL
        curl_easy_setopt(curl, CURLOPT_URL, "https://api.github.com/copilot_internal/v2/token");

        // 使用 HeaderManager 获取请求头
        struct curl_slist* headers = getHeaderManager().getTokenHeaders(longTermToken);
        curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);

        // 设置回调函数接收响应
        curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, WriteCallback);
        curl_easy_setopt(curl, CURLOPT_WRITEDATA, &responseString);

        // 执行请求
        CURLcode res = curl_easy_perform(curl);

        // 检查请求是否成功
        if (res != CURLE_OK) {
            std::cerr << "curl_easy_perform() failed: " << curl_easy_strerror(res) << std::endl;
        }
        else {
            long response_code;
            curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &response_code);

            if (response_code == 200) {
                try {
                    json jsonObject = json::parse(responseString);
                    if (jsonObject.contains("token")) {
                        std::string token = jsonObject["token"];
                        std::cout << "\nNew Token:\n " << token << std::endl;
                        curl_slist_free_all(headers);
                        curl_easy_cleanup(curl);
                        return token;
                    }
                    else {
                        std::cout << "\"token\" field not found in the response." << std::endl;
                    }
                }
                catch (const std::exception& e) {
                    std::cerr << "JSON parsing error: " << e.what() << std::endl;
                }
            }
            else {
                std::cout << "Request failed, status code: " << response_code << std::endl;
                std::cout << "Response body: " << responseString << std::endl;
            }
        }

        // 清理
        curl_slist_free_all(headers);
        curl_easy_cleanup(curl);
    }

    return "";
}

// 获取有效的临时 token
std::string getValidTempToken(const std::string& longTermToken) {
    std::lock_guard<std::mutex> lock(token_mutex);

    TokenManager tokenManager;
    std::string tempToken = tokenManager.getTempToken(longTermToken);

    // 获取并打印用户名
    std::string username = tokenManager.getUsername(longTermToken);
    if (!username.empty()) {
        std::cout << "Login in as: " << username << std::endl;
    }

    if (tempToken.empty() || isTokenExpired(tempToken)) {
        std::cout << "Token has expired or is empty" << std::endl;
        std::string newTempToken = GetToken(longTermToken);
        if (newTempToken.empty()) {
            throw std::runtime_error("Unable to generate a new temporary token.");
        }

        int newExpiry = extractTimestamp(newTempToken);
        bool updated = false;

        // 如果长期 token 存在，更新临时 token
        if (tokenManager.isLongTermTokenExists(longTermToken)) {
            updated = tokenManager.updateTempToken(longTermToken, newTempToken, newExpiry);

            // 如果用户名为空，尝试获取并更新用户名
            if (username.empty()) {
                std::string fetchedUsername = fetchUsername(longTermToken);
                if (!fetchedUsername.empty()) {
                    tokenManager.updateUsername(longTermToken, fetchedUsername);
                }
            }
        }
        else {
            // 获取用户名并创建新记录
            std::string fetchedUsername = fetchUsername(longTermToken);
            updated = tokenManager.addLongTermToken(longTermToken, newTempToken, newExpiry, fetchedUsername);
        }

        if (!updated) {
            throw std::runtime_error("Unable to update temporary token.");
        }

        return newTempToken;
    }
    else {
        return tempToken;
    }
}
