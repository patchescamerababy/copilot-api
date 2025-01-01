# copilot2api
Copilot to OpenAI API

 <a href="README_ZH.md">简体中文</a>
 
## Supported AP

- `GET /v1/models`: Get model list
- `POST /v1/chat/completions`: Chat API
- `POST /v1/embeddings`

## How To Use
0. **RECOMMEND**: <a href="https://github.com/signup">Sign up</a> a new account
1. Run *Get-token*.
2. Open the <a href="https://github.com/login/device">URL</a> in the browser and log in to authorize the program to obtain the token
3. Save this token and use in client app
4. Run the server <a href="https://github.com/patchescamerababy/copilot2api/releases/">app</a>

## 
###  Docker

    docker pull patchescamera/copilot2api:latest
    docker run -d -p 8080:80 patchescamera/copilot2api:latest

#### Test
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
## Build Rust based ***Get-Token*** on Linux:
    sudo apt update
    sudo apt install -y libssl-dev pkg-config cargo
    cargo build --release

then
     
    ./target/release/Get-Token
     
## Notice:
1. The program will automatically save all received tokens to *token.db*. And the next time if the client does not provide a token, a random token in token.db is used
2. When `GET /v1/models` does not receive a valid token as an authorization request header and no record of token in token.db, a default JSON is returned.
3. Model *Claude-3.5-sonnet* using stream mode may have issues with responses being truncated. Switching to non-stream mode might alleviate this.

# Warning
1. Please do not log in with a paid account. It is always **recommended** to log in with a **free account**. There is no guarantee that the account will not be banned.
2. It is **not recommended** to run on any public network device
