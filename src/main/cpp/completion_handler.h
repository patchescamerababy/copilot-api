//
// Created by Administrator on 25-3-14.
//
#ifndef COMPLETION_HANDLER_H
#define COMPLETION_HANDLER_H

#include <string>
#include <nlohmann/json.hpp>
#include <httplib.h>

void handleNormalResponse(httplib::Response& res, const std::string& token, nlohmann::json requestJson,bool hasImage);
void handleStreamResponse(httplib::Response& res, const std::string& token, nlohmann::json requestJson, bool isO1,bool hasImage);

#endif // COMPLETION_HANDLER_H
