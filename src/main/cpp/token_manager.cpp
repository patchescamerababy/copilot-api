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

using json = nlohmann::json;

// ȫ�ֻ����������� token �������̰߳�ȫ
std::mutex token_mutex;

// CURL �ص����������ڽ��� HTTP ��Ӧ����
size_t WriteCallback(void* contents, size_t size, size_t nmemb, std::string* s) {
    size_t newLength = size * nmemb;
    try {
        s->append((char*)contents, newLength);
        return newLength;
    } catch(std::bad_alloc& e) {
        return 0;
    }
}

class TokenManager {
private:
    sqlite3* db;

    // ���ӵ� SQLite ���ݿ�
    sqlite3* connect() {
        sqlite3* conn = nullptr;
        int rc = sqlite3_open("tokens.db", &conn);
        if (rc) {
            std::cerr << "Connection error: " << sqlite3_errmsg(conn) << std::endl;
            return nullptr;
        }

        // �������Լ��
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
        // �������ݿ�����
        db = connect();
        if (!db) {
            std::cerr << "Failed to connect to database" << std::endl;
            return;
        }

        // ���� tokens ��
        const char* createTableSQL =
            "CREATE TABLE IF NOT EXISTS tokens ("
            "id INTEGER PRIMARY KEY AUTOINCREMENT, "
            "long_term_token TEXT NOT NULL UNIQUE, "
            "temp_token TEXT, "
            "temp_token_expiry INTEGER"
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

    // ��鳤�� token �Ƿ����
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

    // ����µĳ��� token ��¼
    bool addLongTermToken(const std::string& longTermToken, const std::string& tempToken, long tempTokenExpiry) {
        if (!db) return false;

        sqlite3_stmt* stmt = nullptr;
        const char* insertSQL = "INSERT INTO tokens(long_term_token, temp_token, temp_token_expiry) VALUES(?, ?, ?)";
        int rc = sqlite3_prepare_v2(db, insertSQL, -1, &stmt, nullptr);

        if (rc != SQLITE_OK) {
            std::cerr << "Error preparing statement: " << sqlite3_errmsg(db) << std::endl;
            return false;
        }

        sqlite3_bind_text(stmt, 1, longTermToken.c_str(), -1, SQLITE_STATIC);
        sqlite3_bind_text(stmt, 2, tempToken.c_str(), -1, SQLITE_STATIC);
        sqlite3_bind_int64(stmt, 3, tempTokenExpiry);

        rc = sqlite3_step(stmt);
        sqlite3_finalize(stmt);

        if (rc != SQLITE_DONE) {
            std::cerr << "Error adding long-term token: " << sqlite3_errmsg(db) << std::endl;
            return false;
        }

        std::cout << "Long-term token added successfully." << std::endl;
        return true;
    }

    // ��ȡ������� token
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

        // ���ѡ��һ�� token
        std::random_device rd;
        std::mt19937 gen(rd());
        std::uniform_int_distribution<> distrib(0, tokens.size() - 1);
        return tokens[distrib(gen)];
    }

    // ��ȡ��ʱ token
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

    // ������ʱ token �������ʱ��
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
};

// �� token ����ȡʱ���
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

    // ������һ����
    if (tokenCopy.substr(0, 4) == "exp=") {
        return std::stoi(tokenCopy.substr(4));
    }

    return 0;
}

// ��� token �Ƿ����
bool isTokenExpired(const std::string& token) {
    int exp = extractTimestamp(token);
    int currentEpoch = std::chrono::duration_cast<std::chrono::seconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();

    std::cout << "\n   Current epoch: " << currentEpoch << std::endl;
    std::cout << "Expiration epoch: " << exp << std::endl;

    // ����ʣ��ʱ��
    int remainingSeconds = exp - currentEpoch;
    int minutes = remainingSeconds / 60;
    int seconds = remainingSeconds % 60;

    std::cout << "Remaining: " << minutes << " minutes " << seconds << " seconds" << std::endl;

    return exp < currentEpoch;
}

// ��ȡ�µ���ʱ token
// ע�⣺HeaderManager ʵ������ completion_handler.cpp �ж���
extern HeaderManager& getHeaderManager();

std::string GetToken(const std::string& longTermToken) {
    CURL* curl = curl_easy_init();
    std::string responseString;

    if (curl) {
        // ���� URL
        curl_easy_setopt(curl, CURLOPT_URL, "https://api.github.com/copilot_internal/v2/token");

        // ʹ�� HeaderManager ��ȡ����ͷ
        struct curl_slist* headers = getHeaderManager().getTokenHeaders(longTermToken);
        curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);

        // ���ûص�����������Ӧ
        curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, WriteCallback);
        curl_easy_setopt(curl, CURLOPT_WRITEDATA, &responseString);

        // ִ������
        CURLcode res = curl_easy_perform(curl);

        // ��������Ƿ�ɹ�
        if (res != CURLE_OK) {
            std::cerr << "curl_easy_perform() failed: " << curl_easy_strerror(res) << std::endl;
        } else {
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
                    } else {
                        std::cout << "\"token\" field not found in the response." << std::endl;
                    }
                } catch (const std::exception& e) {
                    std::cerr << "JSON parsing error: " << e.what() << std::endl;
                }
            } else {
                std::cout << "Request failed, status code: " << response_code << std::endl;
                std::cout << "Response body: " << responseString << std::endl;
            }
        }

        // ����
        curl_slist_free_all(headers);
        curl_easy_cleanup(curl);
    }

    return "";
}

// ��ȡ��Ч����ʱ token
std::string getValidTempToken(const std::string& longTermToken) {
    std::lock_guard<std::mutex> lock(token_mutex);

    TokenManager tokenManager;
    std::string tempToken = tokenManager.getTempToken(longTermToken);

    if (tempToken.empty() || isTokenExpired(tempToken)) {
        std::cout << "Token has expired or is empty" << std::endl;
        std::string newTempToken = GetToken(longTermToken);
        if (newTempToken.empty()) {
            throw std::runtime_error("Unable to generate a new temporary token.");
        }

        int newExpiry = extractTimestamp(newTempToken);
        bool updated = false;

        if (tokenManager.isLongTermTokenExists(longTermToken)) {
            updated = tokenManager.updateTempToken(longTermToken, newTempToken, newExpiry);
        } else {
            updated = tokenManager.addLongTermToken(longTermToken, newTempToken, newExpiry);
        }

        if (!updated) {
            throw std::runtime_error("Unable to update temporary token.");
        }

        return newTempToken;
    } else {
        return tempToken;
    }
}

// sendError �������� global.cpp ��ʵ��
