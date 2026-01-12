/***********************
 * main.cpp
 ***********************/

#include <cstdlib>
#include <iostream>
#include <string>
#include <vector>
#include <thread>
#include <chrono>
#include <set>
#include <locale>
#include <sstream>
#include <iomanip>
#include <ctime>

#ifdef _WIN32
// 避免 <windows.h> 引入旧版 winsock.h，和 cpp-httplib / libcurl 使用的 winsock2.h 冲突
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#ifndef _WINSOCKAPI_
#define _WINSOCKAPI_ // 阻止 windows.h 包含 winsock.h
#endif

#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#endif

// 第三方库
#include <httplib.h>         // https://github.com/yhirose/cpp-httplib
#include <curl/curl.h>       // libcurl
#include <sqlite3.h>         // 如果您需要 SQLite
#include <nlohmann/json.hpp> // https://github.com/nlohmann/json

// 假设您的 token_manager.h 中声明了 getValidTempToken
#include "token_manager.h"

using json = nlohmann::json;

/*===========================================================
 * 1. 外部函数声明：已修改函数签名，增加 bool hasImage 参数
 *===========================================================*/
extern void handleNormalResponse(httplib::Response& res,
                                 const std::string& token,
                                 const json& requestJson,
                                 bool hasImage);

extern void handleStreamResponse(httplib::Response& res,
                                 const std::string& token,
                                 const json& requestJson,
                                 bool isO1,
                                 bool hasImage);

extern void sendError(httplib::Response& res,
                      const std::string& message,
                      int HTTP_code);

extern void handleEmbeddings(const httplib::Request& req,
                             httplib::Response& res);

extern void handleModels(const httplib::Request& req,
                         httplib::Response& res);

// --------------------------------------------------------------------------
// 下面是与“下载图片并转为 data URI”相关的辅助函数
// --------------------------------------------------------------------------

// 写 body 的回调函数，用于把收到的字节追加到 std::vector<unsigned char>
static size_t writeCallback(void* ptr, size_t size, size_t nmemb, void* userdata) {
    auto* vec = reinterpret_cast<std::vector<unsigned char>*>(userdata);
    auto* dataPtr = reinterpret_cast<unsigned char*>(ptr);
    size_t total = size * nmemb;
    vec->insert(vec->end(), dataPtr, dataPtr + total);
    return total;
}

// 解析 HTTP 响应头部，用来获取 Content-Type
static size_t headerCallback(char* buffer, size_t size, size_t nitems, void* userdata) {
    size_t totalSize = size * nitems;
    std::string headerLine(buffer, totalSize);

    // 判断开头是否是 "Content-Type:"
    if (headerLine.rfind("Content-Type:", 0) == 0) {
        std::string ctValue = headerLine.substr(13);

        // 适当 trim
        while (!ctValue.empty() && (ctValue.front() == ' ' || ctValue.front() == '\t')) {
            ctValue.erase(ctValue.begin());
        }
        while (!ctValue.empty() &&
               (ctValue.back() == '\r' || ctValue.back() == '\n' || ctValue.back() == ' ' ||
                ctValue.back() == '\t')) {
            ctValue.pop_back();
        }

        // 存放到 userdata
        auto* contentType = reinterpret_cast<std::string*>(userdata);
        *contentType = ctValue; // 例如 "image/jpeg"
    }
    return totalSize;
}

// Base64 编码函数（简单实现）
static const char* base64_chars =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    "abcdefghijklmnopqrstuvwxyz"
    "0123456789+/";

static std::string base64Encode(const std::vector<unsigned char>& data) {
    std::string ret;
    size_t dataLen = data.size();
    ret.reserve(((dataLen + 2) / 3) * 4);

    int val = 0;
    int valb = -6;
    for (unsigned char c : data) {
        val = (val << 8) + c;
        valb += 8;
        while (valb >= 0) {
            ret.push_back(base64_chars[(val >> valb) & 0x3F]);
            valb -= 6;
        }
    }
    if (valb > -6) {
        ret.push_back(base64_chars[((val << 8) >> (valb + 8)) & 0x3F]);
    }
    while (ret.size() % 4) {
        ret.push_back('=');
    }
    return ret;
}

// 下载图片并转换为 data URI 格式
static bool downloadImageAndConvertToDataURI(const std::string& imageURL, std::string& outDataURI) {
    CURL* curl = curl_easy_init();
    if (!curl) {
        return false;
    }

    std::vector<unsigned char> imageData; // 用于存放下载的字节
    std::string contentType;              // 用于存放从 HTTP 头中解析到的 content-type

    // 配置 curl
    curl_easy_setopt(curl, CURLOPT_URL, imageURL.c_str());
    curl_easy_setopt(curl, CURLOPT_FOLLOWLOCATION, 1L); // 跟随跳转
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, writeCallback);
    curl_easy_setopt(curl, CURLOPT_WRITEDATA, &imageData);
    curl_easy_setopt(curl, CURLOPT_HEADERFUNCTION, headerCallback);
    curl_easy_setopt(curl, CURLOPT_HEADERDATA, &contentType);
    curl_easy_setopt(curl, CURLOPT_CONNECTTIMEOUT, 10L);
    curl_easy_setopt(curl, CURLOPT_TIMEOUT, 20L);

    CURLcode res = curl_easy_perform(curl);
    bool success = false;
    if (res == CURLE_OK) {
        long responseCode = 0;
        curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &responseCode);
        if (responseCode == 200) {
            // 如果没有拿到正确的 contentType 或不是 image/ 前缀，默认用 image/png
            if (contentType.empty() || contentType.rfind("image/", 0) != 0) {
                contentType = "image/png";
            }
            // 将图片数据进行 base64 编码
            std::string base64Image = base64Encode(imageData);
            // 拼成 data URI
            outDataURI = "data:" + contentType + ";base64," + base64Image;
            success = true;
        }
    }
    curl_easy_cleanup(curl);
    return success;
}

// 遍历 JSON 中的 messages -> content，若发现 type == "image_url" 的字段，
// 并且其 image_url["url"] 非 data:image，尝试下载并替换为 data URI
static void processImagesInJson(json& requestJson, bool& hasImage) {
    // 判断是否有 "messages" 且为数组
    if (!requestJson.contains("messages") || !requestJson["messages"].is_array()) {
        return;
    }

    for (auto& msg : requestJson["messages"]) {
        if (!msg.contains("content")) {
            continue;
        }
        // 如果 content 是数组，则遍历每个 contentItem
        auto& content = msg["content"];
        if (!content.is_array()) {
            continue;
        }

        for (auto& contentItem : content) {
            if (!contentItem.contains("type") || !contentItem["type"].is_string()) {
                continue;
            }
            // 是否是 "image_url"
            if (contentItem["type"] == "image_url" && contentItem.contains("image_url") &&
                contentItem["image_url"].is_object() && contentItem["image_url"].contains("url") &&
                contentItem["image_url"]["url"].is_string()) {
                std::string imageURL = contentItem["image_url"]["url"].get<std::string>();
                // 如果已经是 data:image，就不再处理
                if (imageURL.rfind("data:image/", 0) == 0) {
                    continue;
                }

                // 尝试下载并转换为 data URI
                std::string dataUri;
                if (downloadImageAndConvertToDataURI(imageURL, dataUri)) {
                    // 成功则替换
                    contentItem["image_url"]["url"] = dataUri;
                    hasImage = true;
                }
            }
        }
    }
}

// --------------------------------------------------------------------------
// 彩色日志（参考 chatrun）
// --------------------------------------------------------------------------

static void enableAnsiColorOnWindows() {
#ifdef _WIN32
    // Enable ANSI escape sequences in Windows console
    HANDLE hOut = GetStdHandle(STD_OUTPUT_HANDLE);
    if (hOut == INVALID_HANDLE_VALUE) return;

    DWORD dwMode = 0;
    if (!GetConsoleMode(hOut, &dwMode)) return;

    dwMode |= ENABLE_VIRTUAL_TERMINAL_PROCESSING;
    SetConsoleMode(hOut, dwMode);
#endif
}

static std::string nowTimestamp() {
    auto now = std::chrono::system_clock::now();
    std::time_t now_time = std::chrono::system_clock::to_time_t(now);
    std::stringstream ss;
    ss << std::put_time(std::localtime(&now_time), "%Y-%m-%d %H:%M:%S");
    return ss.str();
}

static const char* colorForStatus(int status) {
    // green for 2xx/3xx, red for 4xx/5xx
    if (status >= 200 && status < 400) return "\033[32m";
    if (status >= 400) return "\033[31m";
    return "\033[0m";
}

static void logResponse(const std::string& endpoint,
                        int status,
                        const std::string& httpVersion,
                        const std::string& remoteIP,
                        int remotePort) {
    std::cout << "[" << nowTimestamp() << "] " << remoteIP << ":" << remotePort << " " << endpoint
              << " " << httpVersion << " " << colorForStatus(status) << status << "\033[0m"
              << std::endl;
}

// --------------------------------------------------------------------------
// HTTP 路由注册
// --------------------------------------------------------------------------

static void setupCommonServerOptions(httplib::Server& server) {
    server.set_read_timeout(60, 0);         // 60s
    server.set_write_timeout(60, 0);        // 60s
    server.set_idle_interval(0, 100000);    // 100ms
    server.set_payload_max_length(1024 * 1024 * 100); // 100MB
}

static void setupRoutes(httplib::Server& server) {
    // 设置 GET 路由，返回欢迎页面
    server.Get("/", [](const httplib::Request&, httplib::Response& res) {
        std::string response =
            "<html><head><title>Welcome to API</title></head>"
            "<body><h1>Welcome to API</h1>"
            "<p>This API is used to interact with the GitHub Copilot model. "
            "You can send messages to the model and receive responses.</p>"
            "</body></html>";
        res.set_header("Content-Type", "text/html; charset=utf-8");
        res.set_content(response, "text/html");
    });

    server.Get("/v1/chat/completions", [](const httplib::Request&, httplib::Response& res) {
        std::string response =
            "<html><head><title>Welcome to API</title></head>"
            "<body><h1>Welcome to API</h1>"
            "<p>This API is used to interact with the GitHub Copilot model. "
            "You can send messages to the model and receive responses.</p>"
            "</body></html>";
        res.set_header("Content-Type", "text/html; charset=utf-8");
        res.set_content(response, "text/html");
    });

    // 处理预检请求
    auto handleOptions = [](const httplib::Request&, httplib::Response& res) {
        res.set_header("Access-Control-Allow-Origin", "*");
        res.set_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        res.set_header("Access-Control-Allow-Headers", "Content-Type, Authorization");
        res.set_header("Connection", "keep-alive");
        res.status = 204; // No Content
    };

    // 为各个路由设置 OPTIONS 处理
    server.Options("/v1/chat/completions", handleOptions);
    server.Options("/v1/embeddings", handleOptions);
    server.Options("/v1/models", handleOptions);

    // 设置 POST 路由
    server.Post("/v1/chat/completions", [](const httplib::Request& req, httplib::Response& res) {
        // 设置 CORS 头
        res.set_header("Access-Control-Allow-Origin", "*");
        res.set_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        res.set_header("Access-Control-Allow-Headers", "Content-Type, Authorization");
        res.set_header("Connection", "keep-alive");

        try {
            // 1. 验证 Authorization 头
            auto auth = req.get_header_value("Authorization");
            if (auth.empty() || auth.substr(0, 7) != "Bearer ") {
                sendError(res, "Token is invalid.", 401);
                logResponse("/v1/chat/completions", res.status, req.version, req.remote_addr, req.remote_port);
                return;
            }

            // 获取长期 token
            std::string longTermToken = auth.substr(7);

            // 验证 token 前缀
            if (!(longTermToken.substr(0, 3) == "ghu" || longTermToken.substr(0, 3) == "gho")) {
                sendError(res, "Invalid token prefix.", 401);
                logResponse("/v1/chat/completions", res.status, req.version, req.remote_addr, req.remote_port);
                return;
            }

            // 2. 获取有效的临时 token
            std::string tempToken;
            try {
                tempToken = getValidTempToken(longTermToken);
            } catch (const std::exception& e) {
                sendError(res, std::string("Token processing failed: ") + e.what(), 500);
                logResponse("/v1/chat/completions", res.status, req.version, req.remote_addr, req.remote_port);
                return;
            }

            if (tempToken.empty()) {
                sendError(res, "Unable to obtain a valid temporary token.", 500);
                logResponse("/v1/chat/completions", res.status, req.version, req.remote_addr, req.remote_port);
                return;
            }

            // 3. 解析请求 JSON
            json requestJson;
            try {
                requestJson = json::parse(req.body);
            } catch (const std::exception& e) {
                sendError(res, std::string("Invalid JSON: ") + e.what(), 400);
                logResponse("/v1/chat/completions", res.status, req.version, req.remote_addr, req.remote_port);
                return;
            }

            // 4. 检查图片并转换为 data URI
            bool hasImage = false;
            processImagesInJson(requestJson, hasImage);

            // 5. 提取参数
            std::string model = requestJson.value("model", "");
            bool isStream = requestJson.value("stream", false);
            bool isO1 = false;

            // 如果是 o1 或 o3 型号，则强制不使用流式
            if (model.rfind("o1", 0) == 0 || model.rfind("o3", 0) == 0) {
                std::cout << "stream: false" << std::endl;
                isO1 = true;
            } else {
                // 否则尊重请求的 stream 参数
                requestJson["stream"] = isStream;
            }

            // 6. 根据是否为流式调用不同处理
            if (isStream) {
                handleStreamResponse(res, tempToken, requestJson, isO1, hasImage);
            } else {
                handleNormalResponse(res, tempToken, requestJson, hasImage);
            }

            logResponse("/v1/chat/completions", res.status, req.version, req.remote_addr, req.remote_port);

        } catch (const std::exception& e) {
            sendError(res, std::string("Internal server error: ") + e.what(), 500);
            logResponse("/v1/chat/completions", res.status, req.version, req.remote_addr, req.remote_port);
        }
    });

    server.Post("/v1/embeddings", [](const httplib::Request& req, httplib::Response& res) {
        handleEmbeddings(req, res);
        logResponse("/v1/embeddings", res.status, req.version, req.remote_addr, req.remote_port);
    });

    server.Get("/v1/models", [](const httplib::Request& req, httplib::Response& res) {
        handleModels(req, res);
        logResponse("/v1/models", res.status, req.version, req.remote_addr, req.remote_port);
    });
}

// --------------------------------------------------------------------------
// main：端口自动递增（参考 chatrun）
// --------------------------------------------------------------------------
int main(int argc, char* argv[]) {
    enableAnsiColorOnWindows();

    // 设置本地化
    std::setlocale(LC_ALL, "en_US.UTF-8");

    int startPort = 80;

    // 解析命令行参数：保持兼容原逻辑（argv[1] 为端口）
    if (argc > 1) {
        try {
            startPort = std::stoi(argv[1]);
            if (startPort < 0 || startPort > 65535) {
                std::cerr << "Invalid port number. Exiting." << std::endl;
                return 1;
            }
        } catch (const std::exception&) {
            std::cout << "Usage: " << argv[0] << " <port>" << std::endl;
            return 1;
        }
    }

    // 初始化 curl
    curl_global_init(CURL_GLOBAL_ALL);

    bool endpoints_printed = false;

    // 从 startPort 开始尝试，一直递增到 65535
    for (int port = startPort; port <= 65535; port++) {
        std::cout << "Trying to start server on port " << port << "..." << std::endl;

        httplib::Server server;
        setupCommonServerOptions(server);
        setupRoutes(server);

        // 使用 bind_to_port + listen_after_bind 以便检测端口是否可用
        if (server.bind_to_port("0.0.0.0", port)) {
            std::cout << "Server successfully bound to port " << port << std::endl;

            if (!endpoints_printed) {
                endpoints_printed = true;
                std::cout << "Available endpoints:" << std::endl;
                std::cout << "  GET  /v1/models" << std::endl;
                std::cout << "  POST /v1/chat/completions" << std::endl;
                std::cout << "  POST /v1/embeddings" << std::endl;
            }

            std::cout << "Starting server..." << std::endl;

            // 阻塞监听；若失败则继续尝试下一个端口
            if (!server.listen_after_bind()) {
                std::cerr << "Failed to listen on port " << port << ", trying next port..." << std::endl;
                continue;
            }

            // 正常退出（server 停止后才会到这里）
            curl_global_cleanup();
            return 0;
        } else {
            std::cout << "Port " << port << " is busy, trying next port..." << std::endl;
        }
    }

    std::cerr << "No available ports found!" << std::endl;
    curl_global_cleanup();
    return 1;
}
