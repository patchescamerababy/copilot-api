package main

import (
	"fmt"
	"math/rand"
	"net/http"
	"time"
)

const (
	openai_organization             = "github-copilot"
	editor_version                  = "vscode/1.98.0-insider"
	editor_plugin_version           = "copilot/1.270.0"
	copilot_language_server_version = "1.270.0"
	x_github_api_version            = "2025-01-21"
	user_agent                      = "GitHubCopilotChat/0.23.2"
)

// setCORSHeaders 设置 CORS 响应头
func setCORSHeaders(w http.ResponseWriter) {
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.Header().Set("Access-Control-Allow-Credentials", "true")
	w.Header().Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
	w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization")
}

// GetCopilotHeaders 构造发送给 Copilot API 的请求头
func GetCopilotHeaders() map[string]string {
	headers := make(map[string]string)
	headers["Content-Type"] = "application/json"
	headers["Connection"] = "keep-alive"
	headers["Editor-Plugin-Version"] = editor_plugin_version
	headers["Editor-Version"] = editor_version
	headers["Openai-Organization"] = openai_organization
	headers["User-Agent"] = user_agent
	headers["VScode-MachineId"] = generateRandomHex(64)
	headers["VScode-SessionId"] = uuid()
	headers["accept"] = "*/*"
	headers["Sec-Fetch-Site"] = "none"
	headers["Sec-Fetch-Mode"] = "no-cors"
	headers["Sec-Fetch-Dest"] = "empty"
	headers["accept-encoding"] = "gzip, deflate, br, zstd"
	headers["X-GitHub-Api-Version"] = x_github_api_version
	headers["X-Request-Id"] = randomXRequestID(32)
	return headers
}

func generateRandomHex(n int) string {
	const hexChars = "0123456789abcdef"
	s := ""
	for i := 0; i < n; i++ {
		s += string(hexChars[rand.Intn(len(hexChars))])
	}
	return s
}

func uuid() string {
	return fmt.Sprintf("%x", rand.Int63())
}

func randomXRequestID(length int) string {
	const chars = "abcdefghijklmnopqrstuvwxyz0123456789"
	s := ""
	for i := 0; i < length; i++ {
		s += string(chars[rand.Intn(len(chars))])
	}
	if len(s) >= 32 {
		return fmt.Sprintf("%s-%s-%s-%s-%s", s[0:8], s[8:12], s[12:16], s[16:20], s[20:32])
	}
	return s
}

func init() {
	// 确保随机数种子初始化
	rand.Seed(time.Now().UnixNano())
}
