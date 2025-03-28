# copilot2api
Copilot to OpenAI API

## 支持的 API
- `GET /v1/models`: 获取模型列表
- `POST /v1/chat/completions`: 聊天 API
- `POST /v1/embeddings`: 文本嵌入


## 使用方法
0. **⚠推荐**: <a href="https://github.com/signup">注册</a>一个新账户
1. 打开 *<a href="https://github.com/patchescamerababy/copilot2api/releases">Get-token</a>* 程序，在浏览器中打开 <a href="https://github.com/login/device">URL</a> 并登录以授权次获取token
2. 保存此token并在客户端应用中使用
3. Serverless部署 或 运行 <a href="https://github.com/patchescamerababy/copilot2api/releases/">服务端程序</a> 或Docker部署

## Serverless
复制 *worker.js* 到Cloudflare Worker

## Docker 

    docker pull patchescamera/copilot2api:latest
    docker run -d -p 8080:80 patchescamera/copilot2api:latest
    

#### 测试
    curl -X POST http://127.0.0.1:8080/v1/chat/completions \
     -H "Authorization: Bearer <Bearer>" \
     -H "Content-Type: application/json" \
     -d '{
           "model": "gpt-4o-mini",
           "messages": [
               {"role": "user", "content": "Hello?"}
           ],
           "temperature": 0.7,
           "max_tokens": 1024,
           "stream": false
         }'
         
## 注意事项：
1. 程序会自动将保存token到 SQlite的*token.db*。下次如果客户端未提供token，将使用 tokens.db 中的随机token
2. 不支持图片传入/生成
3. 传入的所有token都会记录在tokens.db中，请勿传入无效token
4. 当 `GET /v1/models` 未收到有效Bearer请求头时，采用tokens.db中的随机bearer，如果 tokens.db 中没有记录时，将返回默认的 JSON
5. o1系列模型不支持stream流式，如果stream为true本程序只是模拟
6. 请勿使用付费账户登录。**推荐**使用**免费账号**登录。无法保证账户不会被封禁。
7. **不推荐**在公网络设备上运行


# 模型列表:

### 格式:
 
#### id -> 默认版本(也可直接用此作为id)

## 对话
(仅
**gpt-4o-2024-05-13**
支持图片、"gpt-4o"现在默认以"gpt-4o-2024-08-06"发送)

gpt-4o -> gpt-4o-2024-05-13

gpt-4-o-preview -> gpt-4o-2024-05-13

gpt-4o-2024-08-06 -> gpt-4o-2024-08-06

gpt-4 -> gpt-4-0613

gpt-4-0613 -> gpt-4-0613

gpt-4o-mini -> gpt-4o-mini-2024-07-18

gpt-4-0125-preview

gpt-3.5-turbo -> gpt-3.5-turbo-0613

o1 -> o1-2024-12-17

o1-mini -> o1-mini-2024-09-12

claude-3.5-sonnet
## Embeddings

text-embedding-ada-002

text-embedding-3-small-inference -> text-embedding-3-small
