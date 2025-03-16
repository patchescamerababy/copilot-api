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

 // 第三方库
#include <httplib.h>        // https://github.com/yhirose/cpp-httplib
#include <curl/curl.h>      // libcurl
#include <sqlite3.h>        // 如果您需要 SQLite
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
    unsigned char* dataPtr = reinterpret_cast<unsigned char*>(ptr);
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
        while (!ctValue.empty() && (ctValue.back() == '\r' || ctValue.back() == '\n' ||
            ctValue.back() == ' ' || ctValue.back() == '\t')) {
            ctValue.pop_back();
        }

        // 存放到 userdata
        auto* contentType = reinterpret_cast<std::string*>(userdata);
        *contentType = ctValue;  // 例如 "image/jpeg"
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
            if (contentItem["type"] == "image_url" &&
                contentItem.contains("image_url") &&
                contentItem["image_url"].is_object() &&
                contentItem["image_url"].contains("url") &&
                contentItem["image_url"]["url"].is_string())
            {
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
// HTTP 服务器类
// --------------------------------------------------------------------------
class Server {
public:
    Server(int port) : server() {
        // 设置 GET 路由，返回欢迎页面
        server.Get("/", [](const httplib::Request&, httplib::Response& res) {
            std::string response = "<html><head><title>Welcome to API</title></head>"
                "<body><h1>Welcome to API</h1>"
                "<p>This API is used to interact with the GitHub Copilot model. "
                "You can send messages to the model and receive responses.</p>"
                "</body></html>";
            res.set_header("Content-Type", "text/html; charset=utf-8");
            res.set_content(response, "text/html");
            });

        server.Get("/v1/chat/completions", [](const httplib::Request&, httplib::Response& res) {
            std::string response = "<html><head><title>Welcome to API</title></head>"
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
            handleChatCompletions(req, res);
            });

        server.Post("/v1/embeddings", [](const httplib::Request& req, httplib::Response& res) {
            handleEmbeddings(req, res);
            });

        server.Get("/v1/models", [](const httplib::Request& req, httplib::Response& res) {
            handleModels(req, res);
            });

        // 启动监听
        if (!server.listen("0.0.0.0", port)) {
            std::cerr << "Failed to start server on port " << port << std::endl;
            exit(1);
        }
    }

private:
    httplib::Server server;

    static void handleChatCompletions(const httplib::Request& req, httplib::Response& res) {
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
                return;
            }

            // 获取长期 token
            std::string longTermToken = auth.substr(7);

            // 验证 token 前缀
            if (!(longTermToken.substr(0, 3) == "ghu" || longTermToken.substr(0, 3) == "gho")) {
                sendError(res, "Invalid token prefix.", 401);
                return;
            }

            // 2. 获取有效的临时 token
            std::string tempToken;
            try {
                tempToken = getValidTempToken(longTermToken);
            }
            catch (const std::exception& e) {
                sendError(res, std::string("Token processing failed: ") + e.what(), 500);
                return;
            }

            if (tempToken.empty()) {
                sendError(res, "Unable to obtain a valid temporary token.", 500);
                return;
            }

            // 3. 解析请求 JSON
            json requestJson;
            try {
                requestJson = json::parse(req.body);
            }
            catch (const std::exception& e) {
                sendError(res, std::string("Invalid JSON: ") + e.what(), 400);
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
            if (model.substr(0, 2) == "o1" || model.substr(0, 2) == "o3") {
                std::cout << "stream: false" << std::endl;
                isO1 = true;
            }
            else {
                // 否则尊重请求的 stream 参数
                requestJson["stream"] = isStream;
            }

            // 6. 根据是否为流式调用不同处理
            if (isStream) {
                // =========== 传入 hasImage ===========
                handleStreamResponse(res, tempToken, requestJson, isO1, hasImage);
            }
            else {
                // =========== 传入 hasImage ===========
                handleNormalResponse(res, tempToken, requestJson, hasImage);
            }

        }
        catch (const std::exception& e) {
            sendError(res, std::string("Internal server error: ") + e.what(), 500);
        }
    }
};

// --------------------------------------------------------------------------
// main 函数
// --------------------------------------------------------------------------
int main(int argc, char* argv[]) {
    // 设置本地化
    std::setlocale(LC_ALL, "en_US.UTF-8");
    int port = 80;

    // 解析命令行参数
    if (argc > 1) {
        try {
            port = std::stoi(argv[1]);
            if (port < 0 || port > 65535) {
                std::cerr << "Invalid port number. Exiting." << std::endl;
                return 1;
            }
        }
        catch (const std::exception&) {
            std::cout << "Usage: " << argv[0] << " <port>" << std::endl;
            return 1;
        }
    }

    // 初始化 curl
    curl_global_init(CURL_GLOBAL_ALL);

    try {
        std::cout << "Server starting on port " << port << "..." << std::endl;
        Server server(port);

        // 保持主线程阻塞
        while (true) {
            std::this_thread::sleep_for(std::chrono::seconds(1));
        }
    }
    catch (const std::exception& e) {
        std::cerr << "Server error: " << e.what() << std::endl;
    }

    // 清理 curl
    curl_global_cleanup();
    return 0;
}
