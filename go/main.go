package main

import (
	"context"
	"encoding/json"
	"fmt"
	"io/ioutil"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"
)

//──────────────────────────────
// 常量定义
//──────────────────────────────

// 用于转发聊天补全请求的 URL
const COPILOT_CHAT_COMPLETIONS_URL = "https://api.individual.githubcopilot.com/chat/completions"

// 用于转发 Embeddings 请求的 URL
const COPILOT_CHAT_EMBEDDINGS_URL = "https://api.individual.githubcopilot.com/embeddings"

// 用于获取模型列表的 URL
const COPILOT_MODELS_URL = "https://api.individual.githubcopilot.com/models"

var (
	initialPort = 8080 // 默认初始端口设置为8080，避免需要root权限
)

// Function to create HTTP server with port fallback
func createHTTPServer(initialPort int) (*http.Server, int, error) {
	var srv *http.Server
	var finalPort = initialPort

	for finalPort <= 65535 {
		addr := fmt.Sprintf("0.0.0.0:%d", finalPort)
		listener, err := net.Listen("tcp", addr)
		if err != nil {
			if strings.Contains(err.Error(), "address already in use") {
				log.Printf("端口 %d 已被占用，尝试端口 %d\n", finalPort, finalPort+1)
				finalPort++
				continue
			} else {
				return nil, 0, err
			}
		}
		mux := http.NewServeMux()
		mux.HandleFunc("/v1/chat/completions", CompletionHandler)
		mux.HandleFunc("/v1/embeddings", EmbeddingHandler)
		mux.HandleFunc("/v1/models", ModelsHandler)
		srv = &http.Server{
			Handler: mux,
		}

		log.Printf("服务器已启动，监听端口 %d\n", finalPort)
		go func() {
			if err := srv.Serve(listener); err != nil && err != http.ErrServerClosed {
				log.Fatalf("服务器启动失败: %v\n", err)
			}
		}()
		return srv, finalPort, nil
	}

	return nil, 0, fmt.Errorf("所有端口从 %d 到 65535 都被占用，无法启动服务器", initialPort)
}

func main() {
	initTokenManager()
	initModels()
	// 解析命令行参数
	if len(os.Args) > 1 {
		p, err := strconv.Atoi(os.Args[1])
		if err == nil && p > 0 && p <= 65535 {
			initialPort = p
		} else {
			log.Printf("无效的端口号: %s，使用默认端口 %d\n", os.Args[1], initialPort)
		}
	}
	// 创建 HTTP 服务器
	var srv *http.Server
	port := initialPort
	for {
		var err error
		srv, _, err = createHTTPServer(port)
		if err == nil {
			break // 端口绑定成功，退出循环
		}
		log.Printf("端口 %d 被占用，尝试下一个端口...\n", port)
		port++
		if port > 65535 {
			log.Printf("端口超过 65535，重置为 1024 继续尝试...\n")
			port = 1024
		}
	}
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, os.Interrupt, syscall.SIGTERM)
	<-quit
	log.Println("正在关闭服务器...")

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := srv.Shutdown(ctx); err != nil {
		log.Fatalf("服务器关闭失败: %v\n", err)
	}

	log.Println("服务器已成功关闭")
}

// ModelsData 存放本地预定义的模型数据，类型为 []map[string]interface{}
var ModelsData []map[string]interface{}

// initModels 初始化本地预定义的模型数据
func initModels() {
	ModelsData = []map[string]interface{}{}

	// Adding models from the new JSON data
	ModelsData = append(ModelsData, map[string]interface{}{
		"preview": false,
		"capabilities": map[string]interface{}{
			"supports": map[string]interface{}{
				"streaming":  true,
				"tool_calls": true,
			},
			"family": "gpt-3.5-turbo",
			"type":   "chat",
			"limits": map[string]interface{}{
				"max_context_window_tokens": 16384,
				"max_prompt_tokens":         12288,
				"max_output_tokens":         4096,
			},
			"object":    "model_capabilities",
			"tokenizer": "cl100k_base",
		},
		"vendor":               "Azure OpenAI",
		"model_picker_enabled": false,
		"name":                 "GPT 3.5 Turbo",
		"id":                   "gpt-3.5-turbo",
		"version":              "gpt-3.5-turbo-0613",
		"object":               "model",
	})

	ModelsData = append(ModelsData, map[string]interface{}{
		"preview": false,
		"capabilities": map[string]interface{}{
			"supports": map[string]interface{}{
				"streaming":  true,
				"tool_calls": true,
			},
			"family": "gpt-3.5-turbo",
			"type":   "chat",
			"limits": map[string]interface{}{
				"max_context_window_tokens": 16384,
				"max_prompt_tokens":         12288,
				"max_output_tokens":         4096,
			},
			"object":    "model_capabilities",
			"tokenizer": "cl100k_base",
		},
		"vendor":               "Azure OpenAI",
		"model_picker_enabled": false,
		"name":                 "GPT 3.5 Turbo",
		"id":                   "gpt-3.5-turbo-0613",
		"version":              "gpt-3.5-turbo-0613",
		"object":               "model",
	})

	ModelsData = append(ModelsData, map[string]interface{}{
		"preview": false,
		"capabilities": map[string]interface{}{
			"supports": map[string]interface{}{
				"streaming":  true,
				"tool_calls": true,
			},
			"family": "gpt-4",
			"type":   "chat",
			"limits": map[string]interface{}{
				"max_context_window_tokens": 32768,
				"max_prompt_tokens":         32768,
				"max_output_tokens":         4096,
			},
			"object":    "model_capabilities",
			"tokenizer": "cl100k_base",
		},
		"vendor":               "Azure OpenAI",
		"model_picker_enabled": false,
		"name":                 "GPT 4",
		"id":                   "gpt-4",
		"version":              "gpt-4-0613",
		"object":               "model",
	})

	ModelsData = append(ModelsData, map[string]interface{}{
		"preview": false,
		"capabilities": map[string]interface{}{
			"supports": map[string]interface{}{
				"streaming":  true,
				"tool_calls": true,
			},
			"family": "gpt-4",
			"type":   "chat",
			"limits": map[string]interface{}{
				"max_context_window_tokens": 32768,
				"max_prompt_tokens":         32768,
				"max_output_tokens":         4096,
			},
			"object":    "model_capabilities",
			"tokenizer": "cl100k_base",
		},
		"vendor":               "Azure OpenAI",
		"model_picker_enabled": false,
		"name":                 "GPT 4",
		"id":                   "gpt-4-0613",
		"version":              "gpt-4-0613",
		"object":               "model",
	})

	ModelsData = append(ModelsData, map[string]interface{}{
		"preview": false,
		"capabilities": map[string]interface{}{
			"supports": map[string]interface{}{
				"streaming":           true,
				"parallel_tool_calls": true,
				"tool_calls":          true,
			},
			"family": "gpt-4o",
			"type":   "chat",
			"limits": map[string]interface{}{
				"vision": map[string]interface{}{
					"max_prompt_images":     1,
					"max_prompt_image_size": 3145728,
				},
				"max_context_window_tokens": 128000,
				"max_prompt_tokens":         64000,
				"max_output_tokens":         4096,
			},
			"object":    "model_capabilities",
			"tokenizer": "o200k_base",
		},
		"vendor":               "Azure OpenAI",
		"model_picker_enabled": true,
		"name":                 "GPT 4o",
		"id":                   "gpt-4o",
		"version":              "gpt-4o-2024-05-13",
		"object":               "model",
	})

	ModelsData = append(ModelsData, map[string]interface{}{
		"preview": false,
		"capabilities": map[string]interface{}{
			"supports": map[string]interface{}{
				"streaming":           true,
				"parallel_tool_calls": true,
				"tool_calls":          true,
			},
			"family": "gpt-4o",
			"type":   "chat",
			"limits": map[string]interface{}{
				"vision": map[string]interface{}{
					"max_prompt_images":     1,
					"max_prompt_image_size": 3145728,
				},
				"max_context_window_tokens": 128000,
				"max_prompt_tokens":         64000,
				"max_output_tokens":         4096,
			},
			"object":    "model_capabilities",
			"tokenizer": "o200k_base",
		},
		"vendor":               "Azure OpenAI",
		"model_picker_enabled": false,
		"name":                 "GPT 4o",
		"id":                   "gpt-4o-2024-05-13",
		"version":              "gpt-4o-2024-05-13",
		"object":               "model",
	})

	ModelsData = append(ModelsData, map[string]interface{}{
		"preview": false,
		"capabilities": map[string]interface{}{
			"supports": map[string]interface{}{
				"streaming":           true,
				"parallel_tool_calls": true,
				"tool_calls":          true,
			},
			"family": "gpt-4o",
			"type":   "chat",
			"limits": map[string]interface{}{
				"max_context_window_tokens": 128000,
				"max_prompt_tokens":         64000,
				"max_output_tokens":         16384,
			},
			"object":    "model_capabilities",
			"tokenizer": "o200k_base",
		},
		"vendor":               "Azure OpenAI",
		"model_picker_enabled": false,
		"name":                 "GPT 4o",
		"id":                   "gpt-4o-2024-08-06",
		"version":              "gpt-4o-2024-08-06",
		"object":               "model",
	})

	ModelsData = append(ModelsData, map[string]interface{}{
		"preview": false,
		"capabilities": map[string]interface{}{
			"supports": map[string]interface{}{
				"streaming":  false,
				"dimensions": true,
			},
			"family": "text-embedding-ada-002",
			"type":   "embeddings",
			"limits": map[string]interface{}{
				"max_inputs": 256,
			},
			"object":    "model_capabilities",
			"tokenizer": "cl100k_base",
		},
		"vendor":               "Azure OpenAI",
		"model_picker_enabled": false,
		"name":                 "Embedding V2 Ada",
		"id":                   "text-embedding-ada-002",
		"version":              "text-embedding-ada-002",
		"object":               "model",
	})

	ModelsData = append(ModelsData, map[string]interface{}{
		"preview": false,
		"capabilities": map[string]interface{}{
			"supports": map[string]interface{}{
				"dimensions": true,
			},
			"family": "text-embedding-3-small",
			"type":   "embeddings",
			"limits": map[string]interface{}{
				"max_inputs": 512,
			},
			"object":    "model_capabilities",
			"tokenizer": "cl100k_base",
		},
		"vendor":               "Azure OpenAI",
		"model_picker_enabled": false,
		"name":                 "Embedding V3 small",
		"id":                   "text-embedding-3-small",
		"version":              "text-embedding-3-small",
		"object":               "model",
	})

	ModelsData = append(ModelsData, map[string]interface{}{
		"preview": false,
		"capabilities": map[string]interface{}{
			"supports": map[string]interface{}{
				"dimensions": true,
			},
			"family":    "text-embedding-3-small",
			"type":      "embeddings",
			"object":    "model_capabilities",
			"tokenizer": "cl100k_base",
		},
		"vendor":               "Azure OpenAI",
		"model_picker_enabled": false,
		"name":                 "Embedding V3 small (Inference)",
		"id":                   "text-embedding-3-small-inference",
		"version":              "text-embedding-3-small",
		"object":               "model",
	})

	ModelsData = append(ModelsData, map[string]interface{}{
		"preview": false,
		"capabilities": map[string]interface{}{
			"supports": map[string]interface{}{
				"streaming":           true,
				"parallel_tool_calls": true,
				"tool_calls":          true,
			},
			"family": "gpt-4o-mini",
			"type":   "chat",
			"limits": map[string]interface{}{
				"max_context_window_tokens": 128000,
				"max_prompt_tokens":         12288,
				"max_output_tokens":         4096,
			},
			"object":    "model_capabilities",
			"tokenizer": "o200k_base",
		},
		"vendor":               "Azure OpenAI",
		"model_picker_enabled": false,
		"name":                 "GPT 4o Mini",
		"id":                   "gpt-4o-mini",
		"version":              "gpt-4o-mini-2024-07-18",
		"object":               "model",
	})

	ModelsData = append(ModelsData, map[string]interface{}{
		"preview": false,
		"capabilities": map[string]interface{}{
			"supports": map[string]interface{}{
				"streaming":           true,
				"parallel_tool_calls": true,
				"tool_calls":          true,
			},
			"family": "gpt-4o-mini",
			"type":   "chat",
			"limits": map[string]interface{}{
				"max_context_window_tokens": 128000,
				"max_prompt_tokens":         12288,
				"max_output_tokens":         4096,
			},
			"object":    "model_capabilities",
			"tokenizer": "o200k_base",
		},
		"vendor":               "Azure OpenAI",
		"model_picker_enabled": false,
		"name":                 "GPT 4o Mini",
		"id":                   "gpt-4o-mini-2024-07-18",
		"version":              "gpt-4o-mini-2024-07-18",
		"object":               "model",
	})

	ModelsData = append(ModelsData, map[string]interface{}{
		"preview": true,
		"capabilities": map[string]interface{}{
			"supports": map[string]interface{}{
				"streaming":          true,
				"structured_outputs": true,
				"tool_calls":         true,
			},
			"family": "o3-mini",
			"type":   "chat",
			"limits": map[string]interface{}{
				"max_context_window_tokens": 200000,
				"max_prompt_tokens":         20000,
				"max_output_tokens":         100000,
			},
			"object":    "model_capabilities",
			"tokenizer": "o200k_base",
		},
		"vendor":               "Azure OpenAI",
		"model_picker_enabled": true,
		"name":                 "o3-mini (Preview)",
		"id":                   "o3-mini",
		"version":              "o3-mini-2025-01-31",
		"object":               "model",
	})

	ModelsData = append(ModelsData, map[string]interface{}{
		"preview": true,
		"capabilities": map[string]interface{}{
			"supports": map[string]interface{}{
				"streaming":          true,
				"structured_outputs": true,
				"tool_calls":         true,
			},
			"family": "o3-mini",
			"type":   "chat",
			"limits": map[string]interface{}{
				"max_context_window_tokens": 200000,
				"max_prompt_tokens":         20000,
				"max_output_tokens":         100000,
			},
			"object":    "model_capabilities",
			"tokenizer": "o200k_base",
		},
		"vendor":               "Azure OpenAI",
		"model_picker_enabled": false,
		"name":                 "o3-mini (Preview)",
		"id":                   "o3-mini-2025-01-31",
		"version":              "o3-mini-2025-01-31",
		"object":               "model",
	})

	ModelsData = append(ModelsData, map[string]interface{}{
		"preview": true,
		"capabilities": map[string]interface{}{
			"supports": map[string]interface{}{
				"streaming":          true,
				"structured_outputs": true,
				"tool_calls":         true,
			},
			"family": "o3-mini",
			"type":   "chat",
			"limits": map[string]interface{}{
				"max_context_window_tokens": 200000,
				"max_prompt_tokens":         20000,
				"max_output_tokens":         100000,
			},
			"object":    "model_capabilities",
			"tokenizer": "o200k_base",
		},
		"vendor":               "Azure OpenAI",
		"model_picker_enabled": false,
		"name":                 "o3-mini (Preview)",
		"id":                   "o3-mini-paygo",
		"version":              "o3-mini-paygo",
		"object":               "model",
	})

	ModelsData = append(ModelsData, map[string]interface{}{
		"preview": true,
		"capabilities": map[string]interface{}{
			"supports": map[string]interface{}{
				"streaming":           true,
				"parallel_tool_calls": true,
				"tool_calls":          true,
			},
			"family": "claude-3.5-sonnet",
			"type":   "chat",
			"limits": map[string]interface{}{
				"vision": map[string]interface{}{
					"max_prompt_images":     1,
					"max_prompt_image_size": 3145728,
				},
				"max_context_window_tokens": 90000,
				"max_prompt_tokens":         90000,
				"max_output_tokens":         8192,
			},
			"object":    "model_capabilities",
			"tokenizer": "o200k_base",
		},
		"vendor":               "Anthropic",
		"model_picker_enabled": true,
		"name":                 "Claude 3.5 Sonnet (Preview)",
		"id":                   "claude-3.5-sonnet",
		"version":              "claude-3.5-sonnet",
		"object":               "model",
		"policy": map[string]interface{}{
			"terms": "Enable access to the latest Claude 3.5 Sonnet model from Anthropic. [Learn more about how GitHub Copilot serves Claude 3.5 Sonnet](https://docs.github.com/copilot/using-github-copilot/using-claude-sonnet-in-github-copilot).",
			"state": "enabled",
		},
	})

	ModelsData = append(ModelsData, map[string]interface{}{
		"preview": true,
		"capabilities": map[string]interface{}{
			"supports": map[string]interface{}{
				"streaming": true,
			},
			"family": "gemini-2.0-flash",
			"type":   "chat",
			"limits": map[string]interface{}{
				"vision": map[string]interface{}{
					"max_prompt_images":     1,
					"max_prompt_image_size": 3145728,
				},
				"max_context_window_tokens": 1000000,
				"max_prompt_tokens":         128000,
				"max_output_tokens":         8192,
			},
			"object":    "model_capabilities",
			"tokenizer": "o200k_base",
		},
		"vendor":               "Google",
		"model_picker_enabled": true,
		"name":                 "Gemini 2.0 Flash (Preview)",
		"id":                   "gemini-2.0-flash-001",
		"version":              "gemini-2.0-flash-001",
		"object":               "model",
		"policy": map[string]interface{}{
			"terms": "Enable access to the latest Gemini models from Google. [Learn more about how GitHub Copilot serves Gemini 2.0 Flash](https://docs.github.com/en/copilot/using-github-copilot/ai-models/using-gemini-flash-in-github-copilot).",
			"state": "enabled",
		},
	})
}

// fetchModels 通过外部 API 获取最新的模型列表，传入有效的短期 token 后返回模型数据列表
func fetchModels(token string) ([]map[string]interface{}, error) {
	req, err := http.NewRequest("GET", COPILOT_MODELS_URL, nil)
	if err != nil {
		return nil, err
	}
	// 设置请求头
	req.Header.Set("authorization", "Bearer "+token)
	req.Header.Set("editor-version", editor_version)
	req.Header.Set("openai-intent", "model-access")
	req.Header.Set("openai-organization", openai_organization)
	req.Header.Set("editor-plugin-version", editor_plugin_version)
	req.Header.Set("copilot-language_server_version", copilot_language_server_version)

	req.Header.Set("x-github-api-version", x_github_api_version)
	req.Header.Set("user-agent", user_agent)
	req.Header.Set("Sec-Fetch-Site", "none")
	req.Header.Set("Sec-Fetch-Mode", "no-cors")
	req.Header.Set("Sec-Fetch-Desc", "empty")
	req.Header.Set("accept", "*/*")
	req.Header.Set("accept-encoding", "gzip, deflate, br zstd")
	req.Header.Set("Connection", "close")

	client := &http.Client{Timeout: 60 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("failed to fetch models, status code: %d", resp.StatusCode)
	}
	body, err := ioutil.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}
	var result map[string]interface{}
	if err := json.Unmarshal(body, &result); err != nil {
		return nil, err
	}
	data, ok := result["data"].([]interface{})
	if !ok {
		return nil, fmt.Errorf("invalid data format")
	}
	models := []map[string]interface{}{}
	for _, m := range data {
		if modelMap, ok := m.(map[string]interface{}); ok {
			models = append(models, modelMap)
		}
	}
	return models, nil
}
