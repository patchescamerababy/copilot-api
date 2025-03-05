package main

import (
	"bufio"
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"io/ioutil"
	"log"
	"net/http"
	"strings"
)

// CompletionHandler 实现 /v1/chat/completions 接口
func CompletionHandler(w http.ResponseWriter, r *http.Request) {
	setCORSHeaders(w)
	if r.Method == http.MethodOptions {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	if r.Method == http.MethodGet {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		io.WriteString(w, `<html><head><title>Welcome to API</title></head>
		<body>
		<h1>Welcome to API</h1>
		<p>This API is used to interact with the GitHub Copilot model. You can send messages to the model and receive responses.</p>
		</body></html>`)
		return
	}
	if r.Method != http.MethodPost {
		http.Error(w, "Method Not Allowed", http.StatusMethodNotAllowed)
		return
	}

	// 解析请求体 JSON
	body, err := ioutil.ReadAll(r.Body)
	if err != nil {
		http.Error(w, "Failed to read request body", http.StatusInternalServerError)
		return
	}
	var reqJSON map[string]interface{}
	if err := json.Unmarshal(body, &reqJSON); err != nil {
		http.Error(w, "Invalid JSON", http.StatusBadRequest)
		return
	}

	// 获取 Bearer token（长期 token 转换成短期 token）
	authHeader := r.Header.Get("Authorization")
	tempToken, err := getTokenFromHeader(authHeader, w)
	if err != nil {
		return
	}

	// Parse JSON
	var requestJson map[string]interface{}
	// Process messages
	messages, ok := requestJson["messages"].([]interface{})
	model, _ := getString(requestJson, "model", "gpt-4o")
	isStream, _ := getBool(requestJson, "stream", false)
	hasImage := false

	if ok {
		var newMessages []interface{}
		for _, msg := range messages {
			message, ok := msg.(map[string]interface{})
			if !ok {
				continue
			}
			_, _ = getString(message, "role", "")
			if content, exists := message["content"]; exists {
				switch contentTyped := content.(type) {
				case []interface{}:
					var _ strings.Builder
					for _, contentItem := range contentTyped {
						contentMap, ok := contentItem.(map[string]interface{})
						if !ok {
							continue
						}
						if msgType, exists := contentMap["type"].(string); exists {
							if msgType == "image_url" {
								if _, exists := contentMap["url"].(string); exists {
									hasImage = true
								}
							}
						}
					}
				}
			}
			newMessages = append(newMessages, message)
		}
		requestJson["messages"] = newMessages

		if len(newMessages) == 0 {
			sendError(w, http.StatusBadRequest, "所有消息的内容均为空。")
			return
		}
	}


	log.Printf("Received completion request for model: %s\n", model)
	//isStream, _ := reqJSON["stream"].(bool)
	isO1 := false
	if strings.HasPrefix(model, "o1") {
		log.Println("Disabling stream for o1 models")
		isO1 = true
		isStream = false
	} else {
		reqJSON["stream"] = isStream
	}

	// 构造转发的请求头
	headers := GetCopilotHeaders()
	headers["openai-intent"] = "conversation-panel"
	headers["Authorization"] = "Bearer " + tempToken
	if hasImage {
		headers["copilot-vision-request"] = "true"
	}else{
		headers["copilot-vision-request"] = "false"
	}


	// 根据是否为流式响应调用不同处理方法
	if isStream {
		if !isO1 {
			forwardStreamResponse(w, reqJSON, headers)
		} else {
			// o1 系列走非流式转发
			forwardNormalResponse(w, reqJSON, headers)
		}
	} else {
		forwardNormalResponse(w, reqJSON, headers)
	}
}

// Utility function to send JSON error responses
func sendError(w http.ResponseWriter, statusCode int, message string) {
	if w.Header().Get("Content-Type") == "" {
		w.Header().Set("Content-Type", "application/json")
	}
	w.WriteHeader(statusCode)
	resp := map[string]string{"error": message}
	jsonResp, _ := json.Marshal(resp)
	_, err := w.Write(jsonResp)
	if err != nil {
		return
	}
}

// forwardNormalResponse 转发非流式请求
func forwardNormalResponse(w http.ResponseWriter, reqJSON map[string]interface{}, headers map[string]string) {
	status, body, err := callCopilotAPI(COPILOT_CHAT_COMPLETIONS_URL, reqJSON, headers)
	if err != nil {
		http.Error(w, "Error forwarding request: "+err.Error(), http.StatusInternalServerError)
		return
	}
	if status != http.StatusOK {
		http.Error(w, fmt.Sprintf("API Error: %d - %s", status, string(body)), status)
		return
	}
	// 直接返回 Copilot API 的响应
	w.Header().Set("Content-Type", "application/json")
	w.Write(body)
}

// forwardStreamResponse 转发流式请求并保证实时刷新输出
func forwardStreamResponse(w http.ResponseWriter, reqJSON map[string]interface{}, headers map[string]string) {
	reqData, err := json.Marshal(reqJSON)
	if err != nil {
		http.Error(w, "Failed to marshal request JSON", http.StatusInternalServerError)
		return
	}
	req, err := http.NewRequest("POST", COPILOT_CHAT_COMPLETIONS_URL, bytes.NewReader(reqData))
	if err != nil {
		http.Error(w, "Failed to create request", http.StatusInternalServerError)
		return
	}
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	// 对流式请求不设置超时
	client := &http.Client{Timeout: 0}
	resp, err := client.Do(req)
	if err != nil {
		http.Error(w, "Failed to call Copilot API: "+err.Error(), http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		body, _ := ioutil.ReadAll(resp.Body)
		http.Error(w, fmt.Sprintf("API Error: %d - %s", resp.StatusCode, string(body)), resp.StatusCode)
		return
	}
	// 设置 SSE 响应头
	w.Header().Set("Content-Type", "text/event-stream; charset=utf-8")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Connection", "keep-alive")
	flusher, ok := w.(http.Flusher)
	if !ok {
		http.Error(w, "Streaming unsupported", http.StatusInternalServerError)
		return
	}
	reader := bufio.NewReader(resp.Body)
	for {
		line, err := reader.ReadString('\n')
		if err != nil {
			if err == io.EOF {
				break
			}
			log.Println("Error reading stream:", err)
			break
		}
		// 只转发 SSE 格式的数据行
		if strings.HasPrefix(line, "data: ") {
			data := strings.TrimSpace(line[6:])
			if data == "[DONE]" {
				fmt.Fprintf(w, "data: %s\n\n", data)
				flusher.Flush()
				break
			}
			// 可在此处对 SSE 数据做进一步处理（例如转换格式），此处直接转发
			fmt.Fprintf(w, "data: %s\n\n", data)
			flusher.Flush()
		}
	}
}

func getInt(m map[string]interface{}, key string, defaultVal int) (int, bool) {
	if val, exists := m[key]; exists {
		switch v := val.(type) {
		case float64:
			return int(v), true
		case int:
			return v, true
		}
	}
	return defaultVal, false
}

func getString(m map[string]interface{}, key string, defaultVal string) (string, bool) {
	if val, exists := m[key]; exists {
		if s, ok := val.(string); ok {
			return s, true
		}
	}
	return defaultVal, false
}

func getBool(m map[string]interface{}, key string, defaultVal bool) (bool, bool) {
	if val, exists := m[key]; exists {
		if b, ok := val.(bool); ok {
			return b, true
		}
	}
	return defaultVal, false
}
