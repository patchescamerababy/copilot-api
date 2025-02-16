package main

import (
	"bytes"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"io/ioutil"
	"log"
	"math/rand"
	"net"
	"net/http"
	"os"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
	_ "github.com/mattn/go-sqlite3"
)

// Constants
const (
	DefaultPort                = 8080
	MaxPort                    = 65535
	CopilotChatCompletionsURL  = "https://api.individual.githubcopilot.com/chat/completions"
	CopilotModelsURL           = "https://api.individual.githubcopilot.com/models"
	CopilotEmbeddingsURL       = "https://api.individual.githubcopilot.com/embeddings"
	TokenDatabasePath          = "tokens.db"
	CopilotAPIConnectTimeout   = 60 * time.Second
	CopilotAPIReadWriteTimeout = 60 * time.Second
)

// Regex for port validation
var numberPattern = regexp.MustCompile(`^-?\d+(\.\d+)?$`)

// Utility functions
func isPort(arg string) bool {
	return arg != "" && numberPattern.MatchString(arg)
}

// HeadersInfo struct to manage headers
type HeadersInfo struct {
	VScodeMachineID string
	UUID            string
}

func NewHeadersInfo() *HeadersInfo {
	return &HeadersInfo{
		VScodeMachineID: generateRandomHex(64),
		UUID:            uuid.New().String(),
	}
}

func generateRandomHex(length int) string {
	bytes := make([]byte, length/2)
	rand.Read(bytes)
	return hex.EncodeToString(bytes)
}

func (h *HeadersInfo) GetCopilotHeaders() map[string]string {
	headers := map[string]string{
		"Content-Type":          "application/json",
		"Editor-Plugin-Version": "copilot-chat/0.23.2",
		"Editor-Version":        "vscode/1.96.1",
		"Openai-Organization":   "github-copilot",
		"User-Agent":            "GitHubCopilotChat/0.23.2",
		"VScode-MachineId":      h.VScodeMachineID,
		"VScode-SessionId":      h.UUID,
		"Accept":                "*/*",
		"Sec-Fetch-Site":        "none",
		"Sec-Fetch-Mode":        "no-cors",
		"Sec-Fetch-Dest":        "empty",
		"Accept-Encoding":       "gzip, deflate, br, zstd",
		"X-GitHub-Api-Version":  "2024-12-15",
		"X-Request-Id":          generateRandomRequestID(32),
	}
	return headers
}

func generateRandomRequestID(length int) string {
	const charset = "abcdefghijklmnopqrstuvwxyz0123456789"
	result := make([]byte, length)
	for i := range result {
		result[i] = charset[rand.Intn(len(charset))]
	}
	return fmt.Sprintf("%s-%s-%s-%s-%s",
		string(result[0:8]),
		string(result[8:12]),
		string(result[12:16]),
		string(result[16:20]),
		string(result[20:32]),
	)
}

// TokenManager struct to manage tokens using SQLite
type TokenManager struct {
	db   *sql.DB
	lock sync.Mutex
}

func NewTokenManager(dbPath string) (*TokenManager, error) {
	db, err := sql.Open("sqlite3", dbPath)
	if err != nil {
		return nil, err
	}

	// Create table if not exists
	createTableSQL := `
	CREATE TABLE IF NOT EXISTS tokens (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		long_term_token TEXT NOT NULL UNIQUE,
		temp_token TEXT,
		temp_token_expiry INTEGER
	);`
	_, err = db.Exec(createTableSQL)
	if err != nil {
		return nil, err
	}

	return &TokenManager{db: db}, nil
}

func (tm *TokenManager) IsLongTermTokenExists(longTermToken string) (bool, error) {
	query := "SELECT id FROM tokens WHERE long_term_token = ?"
	row := tm.db.QueryRow(query, longTermToken)
	var id int
	err := row.Scan(&id)
	if err == sql.ErrNoRows {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	return true, nil
}

func (tm *TokenManager) AddLongTermToken(longTermToken, tempToken string, tempTokenExpiry int64) error {
	insertSQL := "INSERT INTO tokens(long_term_token, temp_token, temp_token_expiry) VALUES(?, ?, ?)"
	_, err := tm.db.Exec(insertSQL, longTermToken, tempToken, tempTokenExpiry)
	return err
}

func (tm *TokenManager) GetRandomLongTermToken() (string, error) {
	query := "SELECT long_term_token FROM tokens"
	rows, err := tm.db.Query(query)
	if err != nil {
		return "", err
	}
	defer rows.Close()

	var tokens []string
	for rows.Next() {
		var token string
		if err := rows.Scan(&token); err != nil {
			return "", err
		}
		tokens = append(tokens, token)
	}

	if len(tokens) == 0 {
		return "", fmt.Errorf("no long-term tokens available")
	}

	rand.Seed(time.Now().UnixNano())
	return tokens[rand.Intn(len(tokens))], nil
}

func (tm *TokenManager) GetTempToken(longTermToken string) (string, error) {
	query := "SELECT temp_token FROM tokens WHERE long_term_token = ?"
	row := tm.db.QueryRow(query, longTermToken)
	var tempToken string
	err := row.Scan(&tempToken)
	if err == sql.ErrNoRows {
		return "", nil
	}
	if err != nil {
		return "", err
	}
	return tempToken, nil
}

func (tm *TokenManager) GetTempTokenExpiry(longTermToken string) (int64, error) {
	query := "SELECT temp_token_expiry FROM tokens WHERE long_term_token = ?"
	row := tm.db.QueryRow(query, longTermToken)
	var expiry int64
	err := row.Scan(&expiry)
	if err == sql.ErrNoRows {
		return 0, nil
	}
	if err != nil {
		return 0, err
	}
	return expiry, nil
}

func (tm *TokenManager) UpdateTempToken(longTermToken, newTempToken string, newExpiry int64) error {
	updateSQL := "UPDATE tokens SET temp_token = ?, temp_token_expiry = ? WHERE long_term_token = ?"
	_, err := tm.db.Exec(updateSQL, newTempToken, newExpiry, longTermToken)
	return err
}

// Utils struct and functions
type Utils struct {
	tokenManager *TokenManager
	headersInfo  *HeadersInfo
	client       *http.Client
}

func NewUtils(tm *TokenManager, hi *HeadersInfo) *Utils {
	return &Utils{
		tokenManager: tm,
		headersInfo:  hi,
		client: &http.Client{
			Timeout: CopilotAPIConnectTimeout,
		},
	}
}

// Token extraction from Authorization header
func (u *Utils) GetToken(authorizationHeader string, w http.ResponseWriter, r *http.Request) (string, error) {
	var longTermToken string

	if authorizationHeader == "" || !strings.HasPrefix(strings.ToLower(authorizationHeader), "bearer ") {
		// Use random long-term token
		token, err := u.tokenManager.GetRandomLongTermToken()
		if err != nil {
			http.Error(w, `{"error":"No valid long-term token available."}`, http.StatusUnauthorized)
			return "", fmt.Errorf("no valid long-term token available")
		}
		longTermToken = token
		log.Println("Using random long-term token:", longTermToken)
	} else {
		// Extract long-term token
		longTermToken = strings.TrimSpace(authorizationHeader[7:])
		if longTermToken == "" {
			http.Error(w, `{"error":"Token is empty."}`, http.StatusUnauthorized)
			return "", fmt.Errorf("token is empty")
		}

		// Check token prefix
		if !strings.HasPrefix(longTermToken, "ghu") && !strings.HasPrefix(longTermToken, "gho") {
			http.Error(w, `{"error":"Invalid token prefix."}`, http.StatusUnauthorized)
			return "", fmt.Errorf("invalid token prefix")
		}

		// Check if long-term token exists
		exists, err := u.tokenManager.IsLongTermTokenExists(longTermToken)
		if err != nil {
			http.Error(w, `{"error":"Internal server error."}`, http.StatusInternalServerError)
			return "", fmt.Errorf("error checking token existence: %v", err)
		}

		if !exists {
			// Fetch new temp token
			newTempToken, err := u.GetTokenFromGitHub(longTermToken)
			if err != nil {
				http.Error(w, fmt.Sprintf(`{"error":"%s"}`, err.Error()), http.StatusUnauthorized)
				return "", fmt.Errorf("failed to fetch new temp token: %v", err)
			}

			expiry := extractTimestamp(newTempToken)
			err = u.tokenManager.AddLongTermToken(longTermToken, newTempToken, expiry)
			if err != nil {
				http.Error(w, `{"error":"Unable to add long-term token."}`, http.StatusInternalServerError)
				return "", fmt.Errorf("failed to add long-term token: %v", err)
			}
		}
	}

	// Get valid temp token
	tempToken, err := u.GetValidTempToken(longTermToken)
	if err != nil {
		http.Error(w, `{"error":"Token processing failed."}`, http.StatusInternalServerError)
		return "", fmt.Errorf("failed to get valid temp token: %v", err)
	}

	if tempToken == "" {
		http.Error(w, `{"error":"Unable to get valid temp token."}`, http.StatusInternalServerError)
		return "", fmt.Errorf("temp token is empty")
	}
	return tempToken, nil
}

// Fetch token from GitHub Copilot API
func (u *Utils) GetTokenFromGitHub(longTermToken string) (string, error) {
	url := "https://api.github.com/copilot_internal/v2/token"

	req, err := http.NewRequest("GET", url, nil)
	if err != nil {
		return "", err
	}

	req.Header.Set("Authorization", "token "+longTermToken)
	req.Header.Set("Editor-Plugin-Version", "copilot-chat/0.23.2")
	req.Header.Set("Editor-Version", "vscode/1.96.1")
	req.Header.Set("User-Agent", "GitHubCopilotChat/0.23.2")
	req.Header.Set("x-github-api-version", "2024-12-15")
	req.Header.Set("Sec-Fetch-Site", "none")
	req.Header.Set("Sec-Fetch-Mode", "no-cors")
	req.Header.Set("Sec-Fetch-Dest", "empty")

	resp, err := u.client.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()

	bodyBytes, err := ioutil.ReadAll(resp.Body)
	if err != nil {
		return "", err
	}

	// 打印响应体以进行调试
	log.Printf("GetTokenFromGitHub Response Body: %s\n", string(bodyBytes))

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return "", fmt.Errorf("failed to fetch token, status: %d, body: %s", resp.StatusCode, string(bodyBytes))
	}

	// 使用 map[string]interface{} 以处理不同的数据类型
	var result map[string]interface{}
	err = json.Unmarshal(bodyBytes, &result)
	if err != nil {
		return "", fmt.Errorf("failed to parse JSON response: %v", err)
	}

	// 检查是否存在 "token" 字段
	tokenValue, exists := result["token"]
	if !exists {
		// 检查是否有 "error" 字段
		if errorMsg, ok := result["error"].(string); ok {
			return "", fmt.Errorf("API error: %s", errorMsg)
		}
		return "", fmt.Errorf(`"token" field not found in response`)
	}

	// 确保 "token" 是字符串类型
	token, ok := tokenValue.(string)
	if !ok {
		return "", fmt.Errorf(`"token" field is not a string`)
	}

	log.Println("New Token:", token)
	return token, nil
}

// GetValidTempToken retrieves a valid temp token or fetches a new one if expired
func (u *Utils) GetValidTempToken(longTermToken string) (string, error) {
	u.tokenManager.lock.Lock()
	defer u.tokenManager.lock.Unlock()

	tempToken, err := u.tokenManager.GetTempToken(longTermToken)
	if err != nil {
		return "", err
	}

	if isTokenExpired(tempToken) {
		log.Println("Temp token expired, fetching a new one.")
		newTempToken, err := u.GetTokenFromGitHub(longTermToken)
		if err != nil {
			return "", err
		}
		newExpiry := extractTimestamp(newTempToken)
		err = u.tokenManager.UpdateTempToken(longTermToken, newTempToken, newExpiry)
		if err != nil {
			return "", err
		}
		return newTempToken, nil
	}

	return tempToken, nil
}

// Extract timestamp from token string
func extractTimestamp(input string) int64 {
	parts := strings.Split(input, ";")
	for _, part := range parts {
		if strings.HasPrefix(part, "exp=") {
			expStr := strings.TrimPrefix(part, "exp=")
			exp, err := strconv.ParseInt(expStr, 10, 64)
			if err == nil {
				return exp
			}
		}
	}
	return 0
}

// Check if token is expired
func isTokenExpired(token string) bool {
	exp := extractTimestamp(token)
	currentEpoch := time.Now().Unix()
	log.Printf("Current epoch: %d, Token expiry: %d\n", currentEpoch, exp)

	return exp < currentEpoch
}

// ModelService struct to manage models
type ModelService struct {
	Models []map[string]interface{}
}

func NewModelService() *ModelService {
	// Initialize with predefined models
	ms := &ModelService{
		Models: []map[string]interface{}{},
	}
	ms.initializeModels()
	return ms
}

func (ms *ModelService) initializeModels() {
	// Clearing existing models if any
	ms.Models = []map[string]interface{}{}

	// Adding models from the new JSON data
	ms.Models = append(ms.Models, map[string]interface{}{
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

	ms.Models = append(ms.Models, map[string]interface{}{
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

	ms.Models = append(ms.Models, map[string]interface{}{
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

	ms.Models = append(ms.Models, map[string]interface{}{
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

	ms.Models = append(ms.Models, map[string]interface{}{
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

	ms.Models = append(ms.Models, map[string]interface{}{
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

	ms.Models = append(ms.Models, map[string]interface{}{
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

	ms.Models = append(ms.Models, map[string]interface{}{
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

	ms.Models = append(ms.Models, map[string]interface{}{
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

	ms.Models = append(ms.Models, map[string]interface{}{
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

	ms.Models = append(ms.Models, map[string]interface{}{
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

	ms.Models = append(ms.Models, map[string]interface{}{
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

	ms.Models = append(ms.Models, map[string]interface{}{
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

	ms.Models = append(ms.Models, map[string]interface{}{
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

	ms.Models = append(ms.Models, map[string]interface{}{
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

	ms.Models = append(ms.Models, map[string]interface{}{
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

	ms.Models = append(ms.Models, map[string]interface{}{
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

// Server struct to encapsulate dependencies
type Server struct {
	headersInfo *HeadersInfo
	tokenMgr    *TokenManager
	utils       *Utils
	modelSvc    *ModelService
	client      *http.Client
}

func NewServer(headersInfo *HeadersInfo, tm *TokenManager, ms *ModelService) *Server {
	utils := NewUtils(tm, headersInfo)
	return &Server{
		headersInfo: headersInfo,
		tokenMgr:    tm,
		utils:       utils,
		modelSvc:    ms,
		client: &http.Client{
			Timeout: CopilotAPIConnectTimeout,
		},
	}
}

// Main function
func main() {
	// Seed the random number generator
	rand.Seed(time.Now().UnixNano())

	// Parse port from command-line arguments
	port := DefaultPort
	if len(os.Args) > 1 {
		if isPort(os.Args[1]) {
			p, err := strconv.Atoi(os.Args[1])
			if err == nil && p > 0 && p <= MaxPort {
				port = p
			} else {
				fmt.Println("Usage: go run main.go <port>")
				os.Exit(1)
			}
		} else {
			fmt.Println("Usage: go run main.go <port>")
			os.Exit(1)
		}
	}

	// Initialize HeadersInfo
	headersInfo := NewHeadersInfo()

	// Initialize TokenManager
	tokenMgr, err := NewTokenManager(TokenDatabasePath)
	if err != nil {
		log.Fatalf("Failed to initialize TokenManager: %v", err)
	}

	// Initialize ModelService
	modelSvc := NewModelService()

	// Initialize Server
	server := NewServer(headersInfo, tokenMgr, modelSvc)

	// Create a new ServeMux
	mux := http.NewServeMux()

	// Register handlers
	mux.HandleFunc("/v1/chat/completions", server.CompletionHandler)
	mux.HandleFunc("/v1/embeddings", server.EmbeddingHandler)
	mux.HandleFunc("/v1/models", server.ModelsHandler)

	// Try binding to the port, increment if in use
	var listener net.Listener
	for {
		addr := fmt.Sprintf("127.0.0.1:%d", port)
		listener, err = net.Listen("tcp", addr)
		if err != nil {
			if strings.Contains(err.Error(), "address already in use") {
				log.Printf("Port %d is already in use. Trying port %d.", port, port+1)
				port++
				if port > MaxPort {
					log.Fatalf("All ports from %d to %d are in use. Exiting.", DefaultPort, MaxPort)
				}
				continue
			} else {
				log.Fatalf("Failed to bind to port %d: %v", port, err)
			}
		}
		break
	}

	log.Printf("Server started on port %d", port)
	if err := http.Serve(listener, mux); err != nil {
		log.Fatalf("Server failed: %v", err)
	}
}

// CompletionHandler handles /v1/chat/completions
func (s *Server) CompletionHandler(w http.ResponseWriter, r *http.Request) {
	// Set CORS headers
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.Header().Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
	w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization")

	if r.Method == http.MethodOptions {
		w.WriteHeader(http.StatusNoContent)
		return
	}

	if r.Method == http.MethodGet {
		// Return welcome page
		response := `<html><head><title>欢迎使用API</title></head><body><h1>欢迎使用API</h1><p>此 API 用于与 GitHub Copilot 模型交互。您可以发送消息给模型并接收响应。</p></body></html>`
		w.Header().Set("Content-Type", "text/html")
		w.WriteHeader(http.StatusOK)
		_, err := w.Write([]byte(response))
		if err != nil {
			log.Printf("Failed to write response: %v", err)
		}
		return
	}

	if r.Method != http.MethodPost {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}

	authorizationHeader := r.Header.Get("Authorization")
	tempToken, err := s.utils.GetToken(authorizationHeader, w, r)
	if err != nil {
		log.Println("Token processing error:", err)
		return
	}

	// Read request body
	body, err := ioutil.ReadAll(r.Body)
	if err != nil {
		log.Println("Failed to read request body:", err)
		http.Error(w, `{"error":"Failed to read request body."}`, http.StatusBadRequest)
		return
	}
	defer r.Body.Close()

	// Parse JSON
	var requestJson map[string]interface{}
	if err := json.Unmarshal(body, &requestJson); err != nil {
		log.Println("Invalid JSON format:", err)
		http.Error(w, `{"error":"Invalid JSON format."}`, http.StatusBadRequest)
		return
	}

	//// Extract parameters
	model, _ := requestJson["model"].(string)
	//if model == "" {
	//	model = "gpt-4o"
	//}
	temperature, _ := requestJson["temperature"].(float64)
	if temperature == 0 {
		temperature = 0.6
	}
	topP, _ := requestJson["top_p"].(float64)
	if topP == 0 {
		topP = 0.9
	}
	maxTokensFloat, _ := requestJson["max_tokens"].(float64)
	maxTokens := int(maxTokensFloat)
	if maxTokens == 0 {
		maxTokens = 4096
	}
	isStream, _ := requestJson["stream"].(bool)

	messages, ok := requestJson["messages"].([]interface{})
	if !ok || len(messages) == 0 {
		http.Error(w, `{"error":"消息内容为空。"}`, http.StatusBadRequest)
		return
	}

	// Limit messages to last 100
	start := 0
	if len(messages) > 100 {
		start = len(messages) - 100
	}
	limitedMessages := messages[start:]

	// Extract user message
	var userContent string
	lastMessage, ok := limitedMessages[len(limitedMessages)-1].(map[string]interface{})
	if ok {
		if role, exists := lastMessage["role"].(string); exists && role == "user" {
			userContent, _ = lastMessage["content"].(string)
		}
	}

	if userContent == "" {
		http.Error(w, `{"error":"用户消息内容为空。"}`, http.StatusBadRequest)
		return
	}

	// Construct new request JSON for Copilot API
	newRequestJson := map[string]interface{}{
		"stream":      isStream,
		"model":       model,
		"max_tokens":  maxTokens,
		"temperature": temperature,
		"top_p":       topP,
		"messages":    limitedMessages,
	}

	if strings.HasPrefix(model, "o1") {
		newRequestJson["stream"] = false
	} else {
		newRequestJson["stream"] = isStream
	}

	// Prepare headers
	copilotHeaders := s.headersInfo.GetCopilotHeaders()
	copilotHeaders["Authorization"] = "Bearer " + tempToken

	// Create HTTP request to Copilot API
	requestBody, _ := json.Marshal(newRequestJson)
	req, err := http.NewRequest("POST", CopilotChatCompletionsURL, bytes.NewBuffer(requestBody))
	if err != nil {
		log.Println("Failed to create Copilot request:", err)
		http.Error(w, `{"error":"Failed to create Copilot request."}`, http.StatusInternalServerError)
		return
	}

	for k, v := range copilotHeaders {
		req.Header.Set(k, v)
	}

	client := &http.Client{Timeout: CopilotAPIConnectTimeout}
	resp, err := client.Do(req)
	if err != nil {
		log.Println("Copilot API request failed:", err)
		http.Error(w, `{"error":"Failed to communicate with Copilot API."}`, http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()

	responseBody, err := ioutil.ReadAll(resp.Body)
	if err != nil {
		log.Println("Failed to read Copilot response body:", err)
		http.Error(w, `{"error":"Failed to read Copilot response."}`, http.StatusBadGateway)
		return
	}

	if resp.StatusCode != http.StatusOK {
		log.Printf("Copilot API error: %d, Body: %s\n", resp.StatusCode, string(responseBody))
		http.Error(w, fmt.Sprintf(`{"error":"API 错误: %d"}`, resp.StatusCode), resp.StatusCode)
		return
	}

	// Handle stream or normal response
	if isStream && !strings.HasPrefix(model, "o1") {
		// Handle stream response
		w.Header().Set("Content-Type", "text/event-stream; charset=utf-8")
		w.Header().Set("Cache-Control", "no-cache")
		w.Header().Set("Connection", "keep-alive")
		w.WriteHeader(http.StatusOK)

		flusher, ok := w.(http.Flusher)
		if !ok {
			log.Println("Streaming unsupported!")
			http.Error(w, "Streaming unsupported!", http.StatusInternalServerError)
			return
		}

		// Write response as is (assuming it's already in SSE format)
		_, err = w.Write(responseBody)
		if err != nil {
			log.Println("Failed to write stream response:", err)
		}
		flusher.Flush()
	} else {
		// Handle normal response
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, err := w.Write(responseBody)
		if err != nil {
			log.Printf("Failed to write response: %v", err)
		}
	}
}

// EmbeddingHandler handles /v1/embeddings
func (s *Server) EmbeddingHandler(w http.ResponseWriter, r *http.Request) {
	// Set CORS headers
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.Header().Set("Access-Control-Allow-Credentials", "true")
	w.Header().Set("Access-Control-Allow-Methods", "POST, OPTIONS")
	w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization")
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Connection", "keep-alive")

	if r.Method == http.MethodOptions {
		w.WriteHeader(http.StatusNoContent)
		return
	}

	if r.Method != http.MethodPost {
		http.Error(w, `{"error":"Method Not Allowed"}`, http.StatusMethodNotAllowed)
		return
	}

	authorizationHeader := r.Header.Get("Authorization")
	tempToken, err := s.utils.GetToken(authorizationHeader, w, r)
	if err != nil {
		log.Println("Token processing error:", err)
		return
	}

	// Read request body
	body, err := ioutil.ReadAll(r.Body)
	if err != nil {
		log.Println("Failed to read request body:", err)
		http.Error(w, `{"error":"Failed to read request body."}`, http.StatusBadRequest)
		return
	}
	defer r.Body.Close()

	// Parse JSON
	var requestJson map[string]interface{}
	if err := json.Unmarshal(body, &requestJson); err != nil {
		log.Println("Invalid JSON format:", err)
		http.Error(w, `{"error":"Invalid JSON format."}`, http.StatusBadRequest)
		return
	}

	// Extract parameters
	model, _ := requestJson["model"].(string)
	if model == "" {
		model = "text-embedding-3-small"
	}

	inputObj := requestJson["input"]
	var inputs []string
	switch v := inputObj.(type) {
	case []interface{}:
		for _, item := range v {
			if str, ok := item.(string); ok {
				inputs = append(inputs, str)
			}
		}
	case string:
		inputs = append(inputs, v)
	default:
		http.Error(w, `{"error":"Invalid input format."}`, http.StatusBadRequest)
		return
	}

	if len(inputs) == 0 {
		http.Error(w, `{"error":"Input cannot be empty."}`, http.StatusBadRequest)
		return
	}

	// Validate model
	modelValid := false
	for _, m := range s.modelSvc.Models {
		if m["id"] == model {

			modelValid = true
			break

		}
	}
	if !modelValid {
		log.Printf("Invalid or unsupported embedding model received: %s. Falling back to default model.", model)
		model = "text-embedding-3-small"
	}

	// Construct request JSON for Copilot API
	newRequestJson := map[string]interface{}{
		"model": model,
		"input": inputs,
	}

	if user, exists := requestJson["user"].(string); exists && user != "" {
		newRequestJson["user"] = user
	}

	// Prepare headers
	copilotHeaders := s.headersInfo.GetCopilotHeaders()
	copilotHeaders["Authorization"] = "Bearer " + tempToken

	// Create HTTP request to Copilot API
	requestBody, _ := json.Marshal(newRequestJson)
	req, err := http.NewRequest("POST", CopilotEmbeddingsURL, bytes.NewBuffer(requestBody))
	if err != nil {
		log.Println("Failed to create Copilot request:", err)
		http.Error(w, `{"error":"Failed to create Copilot request."}`, http.StatusInternalServerError)
		return
	}

	for k, v := range copilotHeaders {
		req.Header.Set(k, v)
	}

	client := &http.Client{Timeout: CopilotAPIConnectTimeout}
	resp, err := client.Do(req)
	if err != nil {
		log.Println("Copilot API request failed:", err)
		http.Error(w, `{"error":"Failed to communicate with Copilot API."}`, http.StatusBadGateway)
		return
	}
	defer func(Body io.ReadCloser) {
		err := Body.Close()
		if err != nil {

		}
	}(resp.Body)

	responseBody, err := ioutil.ReadAll(resp.Body)
	if err != nil {
		log.Println("Failed to read Copilot response body:", err)
		http.Error(w, `{"error":"Failed to read Copilot response."}`, http.StatusBadGateway)
		return
	}

	if resp.StatusCode != http.StatusOK {
		log.Printf("Copilot API error: %d, Body: %s\n", resp.StatusCode, string(responseBody))
		http.Error(w, `{"error":"Failed to get embeddings from Copilot API"}`, resp.StatusCode)
		return
	}

	// Return Copilot's response directly
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_, err = w.Write(responseBody)
	if err != nil {
		log.Printf("Failed to write response: %v", err)
	}
}

// ModelsHandler handles /v1/models
func (s *Server) ModelsHandler(w http.ResponseWriter, r *http.Request) {
	// Set CORS headers
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.Header().Set("Access-Control-Allow-Methods", "GET, OPTIONS")
	w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization")

	if r.Method == http.MethodOptions {
		w.WriteHeader(http.StatusNoContent)
		return
	}

	if r.Method != http.MethodGet {
		http.Error(w, `{"error":"Method Not Allowed"}`, http.StatusMethodNotAllowed)
		return
	}

	authorizationHeader := r.Header.Get("Authorization")
	tempToken, err := s.utils.GetToken(authorizationHeader, w, r)
	if err != nil {
		log.Println("Token processing error:", err)
		return
	}

	var fetchedModels []map[string]interface{}
	if tempToken != "" {
		fetchedModels, err = s.FetchModelsFromCopilot(tempToken)
		if err != nil {
			log.Println("Failed to fetch models from Copilot:", err)
			http.Error(w, `{"error":"无法获取模型列表。"}`, http.StatusInternalServerError)
			return
		}
	} else {
		fetchedModels = s.modelSvc.Models
	}

	// Construct response JSON
	responseJson := map[string]interface{}{
		"data":   fetchedModels,
		"object": "list",
	}

	responseBytes, err := json.Marshal(responseJson)
	if err != nil {
		log.Println("Failed to marshal response JSON:", err)
		http.Error(w, `{"error":"Failed to marshal response."}`, http.StatusInternalServerError)
		return
	}

	// Write response
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_, err = w.Write(responseBytes)
	if err != nil {
		log.Printf("Failed to write response: %v", err)
	}
}

// FetchModelsFromCopilot fetches models from Copilot API
func (s *Server) FetchModelsFromCopilot(tempToken string) ([]map[string]interface{}, error) {
	req, err := http.NewRequest("GET", CopilotModelsURL, nil)
	if err != nil {
		return nil, err
	}

	// Set headers
	copilotHeaders := s.headersInfo.GetCopilotHeaders()
	copilotHeaders["Authorization"] = "Bearer " + tempToken

	for k, v := range copilotHeaders {
		req.Header.Set(k, v)
	}

	resp, err := s.client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		bodyBytes, _ := ioutil.ReadAll(resp.Body)
		return nil, fmt.Errorf("failed to fetch models, status: %d, body: %s", resp.StatusCode, string(bodyBytes))
	}

	var responseJson map[string]interface{}
	if err := json.NewDecoder(resp.Body).Decode(&responseJson); err != nil {
		return nil, err
	}

	data, exists := responseJson["data"].([]interface{})
	if !exists {
		return nil, fmt.Errorf(`"data" field not found in response`)
	}

	var models []map[string]interface{}
	for _, item := range data {
		if model, ok := item.(map[string]interface{}); ok {
			models = append(models, model)
		}
	}

	return models, nil
}

// Helper function to send error responses
func sendErrorResponse(w http.ResponseWriter, statusCode int, message string) {
	errorJson := map[string]interface{}{
		"error": message,
		"code":  statusCode,
	}
	responseBytes, _ := json.Marshal(errorJson)
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(statusCode)
	_, _ = w.Write(responseBytes)
}
