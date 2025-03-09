#include <cstdlib>
#include <iostream>
#include <string>
#include <vector>
#include <thread>
#include <chrono>
#include <httplib.h>
#include <curl/curl.h>
#include <sqlite3.h>
#include <nlohmann/json.hpp>
#include <locale>
#include "token_manager.h"  // ���������� getValidTempToken ������

using json = nlohmann::json;

// �����ⲿ������
extern void handleNormalResponse(httplib::Response& res, const std::string& token, const json& requestJson);
extern void handleStreamResponse(httplib::Response& res, const std::string& token, const json& requestJson, bool isO1);
extern void sendError(httplib::Response& res, const std::string& message, int HTTP_code);
extern void handleEmbeddings(const httplib::Request& req, httplib::Response& res);
extern void handleModels(const httplib::Request& req, httplib::Response& res);

// HTTP ��������
class Server {
public:
    Server(int port) : server() {
        // ���� GET ·�ɣ����ػ�ӭҳ��
        server.Get("/v1/chat/completions", []([[maybe_unused]] const httplib::Request& req, httplib::Response& res) {
            std::string response = "<html><head><title>Welcome to API</title></head>"
                                   "<body><h1>Welcome to API</h1>"
                                   "<p>This API is used to interact with the GitHub Copilot model. You can send messages to the model and receive responses.</p>"
                                   "</body></html>";
            res.set_header("Content-Type", "text/html; charset=utf-8");
            res.set_content(response, "text/html");
        });

        // ����Ԥ������
        auto handleOptions = []([[maybe_unused]] const httplib::Request& req, httplib::Response& res) {
            res.set_header("Access-Control-Allow-Origin", "*");
            res.set_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            res.set_header("Access-Control-Allow-Headers", "Content-Type, Authorization");
            res.set_header("Connection", "keep-alive");
            res.status = 204;
        };

        // Ϊ����·������ OPTIONS ����
        server.Options("/v1/chat/completions", handleOptions);
        server.Options("/v1/embeddings", handleOptions);
        server.Options("/v1/models", handleOptions);

        // ���� POST ·��
        server.Post("/v1/chat/completions", [](const httplib::Request& req, httplib::Response& res) {
            handleChatCompletions(req, res);
        });

        server.Post("/v1/embeddings", [](const httplib::Request& req, httplib::Response& res) {
            handleEmbeddings(req, res);
        });

        server.Post("/v1/models", [](const httplib::Request& req, httplib::Response& res) {
            handleModels(req, res);
        });

        // ����������
        if (!server.listen("0.0.0.0", port)) {
            std::cerr << "Failed to start server on port " << port << std::endl;
            exit(1);
        }
    }

private:
    httplib::Server server;

    static void handleChatCompletions(const httplib::Request& req, httplib::Response& res) {
        // ���� CORS ͷ
        res.set_header("Access-Control-Allow-Origin", "*");
        res.set_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        res.set_header("Access-Control-Allow-Headers", "Content-Type, Authorization");
        res.set_header("Connection", "keep-alive");

        try {
            // ��֤ Authorization ͷ
            auto auth = req.get_header_value("Authorization");
            if (auth.empty() || auth.substr(0, 7) != "Bearer ") {
                sendError(res, "Token is invalid.", 401);
                return;
            }

            // ��ȡ���� token
            std::string longTermToken = auth.substr(7);

            // ��֤ token ǰ׺
            if (!(longTermToken.substr(0, 3) == "ghu" || longTermToken.substr(0, 3) == "gho")) {
                sendError(res, "Invalid token prefix.", 401);
                return;
            }

            // ��ȡ��Ч����ʱ token
            std::string tempToken;
            try {
                tempToken = getValidTempToken(longTermToken);
            } catch (const std::exception& e) {
                sendError(res, std::string("Token processing failed: ") + e.what(), 500);
                return;
            }

            if (tempToken.empty()) {
                sendError(res, "Unable to obtain a valid temporary token.", 500);
                return;
            }

            // ����������
            json requestJson;
            try {
                requestJson = json::parse(req.body);
            } catch (const std::exception& e) {
                sendError(res, std::string("Invalid JSON: ") + e.what(), 400);
                return;
            }

            // ��ȡ����
            std::string model = requestJson.value("model", "");
            bool isStream = requestJson.value("stream", false);

            // ����ģ�͵�������
            bool isO1 = false;
            if (model.substr(0, 2) == "o1" || model.substr(0, 2) == "o3") {
                std::cout << "stream: false" << std::endl;
                isO1 = true;
            } else {
                requestJson["stream"] = isStream;
            }

            // �����Ƿ�Ϊ��ʽ��Ӧ���ò�ͬ�Ĵ�����
            if (isStream) {
                handleStreamResponse(res, tempToken, requestJson, isO1);
            } else {
                handleNormalResponse(res, tempToken, requestJson);
            }

        } catch (const std::exception& e) {
            sendError(res, std::string("Internal server error: ") + e.what(), 500);
        }
    }
};

int main(int argc, char* argv[]) {
    std::setlocale(LC_ALL, "en_US.UTF-8");
    int port = 80;


    // ���������в���
    if (argc > 1) {
        try {
            port = std::stoi(argv[1]);
            if (port < 0 || port > 65535) {
                std::cerr << "Invalid port number. Exiting." << std::endl;
                return 1;
            }
        } catch (const std::exception&) {
            std::cout << "Usage: " << argv[0] << " <port>" << std::endl;
            return 1;
        }
    }
    std::cout << "Server started on port " << port << std::endl;
    // ��ʼ�� CURL
    curl_global_init(CURL_GLOBAL_ALL);

    try {
        Server server(port);
        std::cout << "Server started on port " << port << std::endl;

        // �������߳�����
        while (true) {
            std::cout << "Server started on port " << port << std::endl;
            std::this_thread::sleep_for(std::chrono::seconds(1));
        }
    } catch (const std::exception& e) {
        std::cerr << "Server error: " << e.what() << std::endl;
    }

    // ���� CURL
    curl_global_cleanup();
    return 0;
}
