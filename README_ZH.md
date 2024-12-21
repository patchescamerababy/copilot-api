# copilot2api
Copilot to OpenAI API

## 支持的 API
- `GET /v1/models`: 获取模型列表
- `POST /v1/chat/completions`: 聊天 API
- `POST /v1/embeddings`


## 使用方法
0. **推荐**: <a href="https://github.com/signup">注册</a>一个新账户
1. 首次运行需 *Get-token* 获取token
2. 在浏览器中打开 <a href="https://github.com/login/device">URL</a> 并登录以授权次获取token
3. 保存此token并在客户端应用中使用
4. 运行 <a href="https://github.com/patchescamerababy/copilot2api/releases/">服务器</a>

## 命令
./Copilot2API [端口号]

## 注意事项：
1. 程序会自动将所有接收到的token保存到 *token.db*。下次如果客户端未提供token，将使用 token.db 中的随机令牌
2. 当 `GET /v1/models` 未收到有效令牌作为授权请求头且 token.db 中没有token记录时，将返回默认的 JSON。
3. *Claude-3.5-sonnet* 可能会有网络断流问题

# 警告
1. 请勿使用付费账户登录。**推荐**使用**免费账号**登录。无法保证账户不会被封禁。
2. **不推荐**在任何公共网络设备上运行
