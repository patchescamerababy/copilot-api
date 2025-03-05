package main

import (
	"encoding/json"
	"log"
	"net/http"
)

// ModelsHandler 实现 /v1/models 接口
func ModelsHandler(w http.ResponseWriter, r *http.Request) {
	setCORSHeaders(w)
	if r.Method == http.MethodOptions {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	if r.Method != http.MethodGet {
		http.Error(w, "Method Not Allowed", http.StatusMethodNotAllowed)
		return
	}
	// 默认使用本地模型数据
	fetchedModels := ModelsData
	// 若请求中携带 Authorization，则尝试调用外部 API 获取最新模型数据
	authHeader := r.Header.Get("Authorization")
	if authHeader != "" {
		tempToken, err := getTokenFromHeader(authHeader, w)
		if err == nil && tempToken != "" {
			models, err := fetchModels(tempToken)
			if err == nil {
				fetchedModels = models
			} else {
				log.Println("Failed to fetch models from API, using local data:", err)
			}
		}
	}
	response := map[string]interface{}{
		"data":   fetchedModels,
		"object": "list",
	}
	respData, err := json.Marshal(response)
	if err != nil {
		http.Error(w, "Failed to marshal models data", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Write(respData)
}
