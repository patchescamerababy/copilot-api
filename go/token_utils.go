package main

import (
	"bytes"
	"database/sql"
	"encoding/json"
	"fmt"
	"io/ioutil"
	"log"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	_ "github.com/mattn/go-sqlite3"
)

var tokenManager *TokenManager

// initTokenManager 初始化 TokenManager（SQLite 数据库存储）
func initTokenManager() {
	var err error
	tokenManager, err = NewTokenManager()
	if err != nil {
		log.Fatal("Failed to initialize token manager:", err)
	}
}

// TokenManager 管理长期 token 与短期 token
type TokenManager struct {
	db   *sql.DB
	lock sync.Mutex
}

func NewTokenManager() (*TokenManager, error) {
	db, err := sql.Open("sqlite3", "./tokens.db")
	if err != nil {
		return nil, err
	}
	createTableSQL := `CREATE TABLE IF NOT EXISTS tokens (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		long_term_token TEXT NOT NULL UNIQUE,
		temp_token TEXT,
		temp_token_expiry INTEGER
	);`
	if _, err := db.Exec(createTableSQL); err != nil {
		return nil, err
	}
	return &TokenManager{db: db}, nil
}

func (tm *TokenManager) IsLongTermTokenExists(longTermToken string) bool {
	query := "SELECT id FROM tokens WHERE long_term_token = ?"
	row := tm.db.QueryRow(query, longTermToken)
	var id int
	err := row.Scan(&id)
	return err == nil
}

func (tm *TokenManager) AddLongTermToken(longTermToken, tempToken string, tempTokenExpiry int64) bool {
	insertSQL := "INSERT INTO tokens(long_term_token, temp_token, temp_token_expiry) VALUES(?, ?, ?)"
	_, err := tm.db.Exec(insertSQL, longTermToken, tempToken, tempTokenExpiry)
	if err != nil {
		log.Println("Error adding long-term token:", err)
		return false
	}
	log.Println("Long-term token added successfully.")
	return true
}

func (tm *TokenManager) GetRandomLongTermToken() (string, error) {
	query := "SELECT long_term_token FROM tokens"
	rows, err := tm.db.Query(query)
	if err != nil {
		return "", err
	}
	defer rows.Close()
	tokens := []string{}
	for rows.Next() {
		var token string
		if err := rows.Scan(&token); err == nil {
			tokens = append(tokens, token)
		}
	}
	if len(tokens) == 0 {
		return "", fmt.Errorf("no tokens found")
	}
	// 简单随机选择
	index := time.Now().UnixNano() % int64(len(tokens))
	return tokens[index], nil
}

func (tm *TokenManager) GetTempToken(longTermToken string) (string, error) {
	query := "SELECT temp_token FROM tokens WHERE long_term_token = ?"
	row := tm.db.QueryRow(query, longTermToken)
	var tempToken string
	err := row.Scan(&tempToken)
	if err != nil {
		return "", err
	}
	return tempToken, nil
}

func (tm *TokenManager) UpdateTempToken(longTermToken, newTempToken string, newExpiry int64) bool {
	updateSQL := "UPDATE tokens SET temp_token = ?, temp_token_expiry = ? WHERE long_term_token = ?"
	res, err := tm.db.Exec(updateSQL, newTempToken, newExpiry, longTermToken)
	if err != nil {
		log.Println("Error updating temp token:", err)
		return false
	}
	affected, err := res.RowsAffected()
	if err != nil || affected == 0 {
		return false
	}
	log.Println("Temp token updated successfully.")
	return true
}

// GetToken 调用外部 API 获取新的短期 token
func GetToken(longTermToken string) (string, error) {
	url := "https://api.github.com/copilot_internal/v2/token"
	req, err := http.NewRequest("GET", url, nil)
	if err != nil {
		return "", err
	}
	req.Header.Set("Authorization", "token "+longTermToken)
	req.Header.Set("Editor-Plugin-Version", editor_plugin_version)
	req.Header.Set("Editor-Version", editor_version)
	req.Header.Set("User-Agent", user_agent)
	req.Header.Set("x-github-api-version", x_github_api_version)
	req.Header.Set("Sec-Fetch-Site", "none")
	req.Header.Set("Sec-Fetch-Mode", "no-cors")
	req.Header.Set("Sec-Fetch-Dest", "empty")

	client := &http.Client{Timeout: 60 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	body, _ := ioutil.ReadAll(resp.Body)
	if resp.StatusCode >= 200 && resp.StatusCode < 300 {
		var result map[string]interface{}
		if err := json.Unmarshal(body, &result); err == nil {
			if token, ok := result["token"].(string); ok {
				log.Println("New Token:", token)
				return token, nil
			}
		}
		return "", fmt.Errorf("\"token\" field not found in response")
	}
	return "", fmt.Errorf("Request failed, status code: %d, response: %s", resp.StatusCode, string(body))
}

// extractTimestamp 从 token 字符串中解析过期时间，格式类似 "tid=xxx;exp=1731950502"
func extractTimestamp(token string) int64 {
	parts := strings.Split(token, ";")
	for _, part := range parts {
		if strings.HasPrefix(part, "exp=") {
			t, err := strconv.ParseInt(strings.TrimPrefix(part, "exp="), 10, 64)
			if err == nil {
				return t
			}
		}
	}
	return 0
}

func isTokenExpired(token string) bool {
	exp := extractTimestamp(token)
	current := time.Now().Unix()
	log.Printf("Current: %d, Expiration: %d", current, exp)
	//打印当前时间 年月日时分秒
	log.Printf("Current    time: %s", time.Now().Format("2006-01-02 15:04:05"))
	//过期时间
	log.Printf("Expiration time: %s", time.Unix(exp, 0).Format("2006-01-02 15:04:05"))
	if exp < current {
		//还剩余时间，分:秒
		log.Printf("Remaining time: %s:%s", time.Unix(exp-current, 0).Format("04"), time.Unix(exp-current, 0).Format("05"))
	} else {
		//已经过期
		log.Printf("Token has expired")

	}

	return exp < current
}

func getValidTempToken(longTermToken string) (string, error) {
	tokenManager.lock.Lock()
	defer tokenManager.lock.Unlock()
	tempToken, err := tokenManager.GetTempToken(longTermToken)
	if err != nil || tempToken == "" || isTokenExpired(tempToken) {
		log.Println("Token expired or not found, generating new one")
		newTempToken, err := GetToken(longTermToken)
		if err != nil || newTempToken == "" {
			return "", fmt.Errorf("unable to generate new temporary token")
		}
		newExpiry := extractTimestamp(newTempToken)
		if !tokenManager.UpdateTempToken(longTermToken, newTempToken, newExpiry) {
			return "", fmt.Errorf("unable to update temporary token")
		}
		return newTempToken, nil
	}
	return tempToken, nil
}

func getTokenFromHeader(authHeader string, w http.ResponseWriter) (string, error) {
	var longTermToken string
	if authHeader == "" || !strings.HasPrefix(authHeader, "Bearer ") {
		token, err := tokenManager.GetRandomLongTermToken()
		if err != nil {
			http.Error(w, "No long term token available", http.StatusUnauthorized)
			return "", fmt.Errorf("no long term token available")
		}
		longTermToken = token
		log.Println("Using random long-term token:", longTermToken)
	} else {
		longTermToken = strings.TrimSpace(strings.TrimPrefix(authHeader, "Bearer "))
		if longTermToken == "" {
			http.Error(w, "Token is empty", http.StatusUnauthorized)
			return "", fmt.Errorf("token is empty")
		}
		if !strings.HasPrefix(longTermToken, "ghu") && !strings.HasPrefix(longTermToken, "gho") {
			http.Error(w, "Invalid token prefix", http.StatusUnauthorized)
			return "", fmt.Errorf("invalid token prefix")
		}
		if !tokenManager.IsLongTermTokenExists(longTermToken) {
			newTempToken, err := GetToken(longTermToken)
			if err != nil || newTempToken == "" {
				http.Error(w, "Unable to generate new temporary token", http.StatusInternalServerError)
				return "", fmt.Errorf("unable to generate new temporary token")
			}
			tempExpiry := extractTimestamp(newTempToken)
			if !tokenManager.AddLongTermToken(longTermToken, newTempToken, tempExpiry) {
				http.Error(w, "Unable to add long-term token", http.StatusInternalServerError)
				return "", fmt.Errorf("unable to add long-term token")
			}
		}
	}
	tempToken, err := getValidTempToken(longTermToken)
	if err != nil {
		http.Error(w, "Token processing failed: "+err.Error(), http.StatusInternalServerError)
		return "", err
	}
	log.Printf("%s", tempToken)
	return tempToken, nil
}

func callCopilotAPI(url string, reqJSON map[string]interface{}, headers map[string]string) (int, []byte, error) {
	// 将请求体转为 JSON 数据
	jsonData, err := json.Marshal(reqJSON)
	if err != nil {
		return 0, nil, err
	}
	// 构造 POST 请求
	req, err := http.NewRequest("POST", url, bytes.NewReader(jsonData))
	if err != nil {
		return 0, nil, err
	}
	// 设置请求头
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	// 设置超时（60 秒）
	client := &http.Client{Timeout: 60 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return 0, nil, err
	}
	defer resp.Body.Close()
	body, err := ioutil.ReadAll(resp.Body)
	if err != nil {
		return resp.StatusCode, nil, err
	}
	return resp.StatusCode, body, nil
}
