package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io/ioutil"
	"log"
	"net/http"
)

// COPILOT_CHAT_EMBEDDINGS_URL 用于转发 Embedding 请求
//const COPILOT_CHAT_EMBEDDINGS_URL = "https://api.individual.githubcopilot.com/embeddings"

// EmbeddingHandler 实现 /v1/embeddings 接口，参考 Java 版本的处理逻辑
func EmbeddingHandler(w http.ResponseWriter, r *http.Request) {
	// 设置 CORS 响应头
	setCORSHeaders(w)

	if r.Method == http.MethodOptions {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	if r.Method != http.MethodPost {
		http.Error(w, "Method Not Allowed", http.StatusMethodNotAllowed)
		return
	}

	// 读取请求体
	body, err := ioutil.ReadAll(r.Body)
	if err != nil {
		http.Error(w, "Failed to read request body", http.StatusInternalServerError)
		return
	}
	log.Println("Received Embedding Request JSON:")
	log.Println(formatJSON(string(body)))

	// 解析 JSON 请求体
	var reqJSON map[string]interface{}
	if err := json.Unmarshal(body, &reqJSON); err != nil {
		http.Error(w, "Invalid JSON", http.StatusBadRequest)
		return
	}

	// 从请求 Header 中获取 token（调用辅助函数 getTokenFromHeader）
	authHeader := r.Header.Get("Authorization")
	tempToken, err := getTokenFromHeader(authHeader, w)
	if err != nil {
		// getTokenFromHeader 内部已经返回错误信息给客户端
		return
	}

	// 构造转发给 Copilot API 的请求头
	headers := GetCopilotHeaders()
	headers["Authorization"] = "Bearer " + tempToken

	// 调用辅助函数 callCopilotAPI 转发请求到 Embeddings API
	status, respBody, err := callCopilotAPI(COPILOT_CHAT_EMBEDDINGS_URL, reqJSON, headers)
	if err != nil {
		http.Error(w, fmt.Sprintf("Failed to get embeddings: %s", err.Error()), http.StatusBadGateway)
		return
	}
	if status != http.StatusOK {
		http.Error(w, fmt.Sprintf("API Error: %d - %s", status, string(respBody)), status)
		return
	}

	// 格式化输出返回结果日志（便于调试）
	log.Println("Received Embedding Response from Copilot API:")
	log.Println(formatJSON(string(respBody)))

	// 返回 Copilot API 的响应给客户端
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(http.StatusOK)
	w.Write(respBody)
}

// formatJSON 对 JSON 字符串进行格式化（缩进）以便输出日志
func formatJSON(jsonStr string) string {
	var buf bytes.Buffer
	if err := json.Indent(&buf, []byte(jsonStr), "", "  "); err != nil {
		return jsonStr
	}
	return buf.String()
}
