# copilot2api
Copilot to OpenAI API

 <a href="README_ZH.md">简体中文</a>
 
## Supported API

- `GET /v1/models`: Get model list
- `POST /v1/chat/completions`: Chat API
- `POST /v1/embeddings`: Text Embedding

## How To Use
0. ⚠**RECOMMEND**: <a href="https://github.com/signup">Sign up</a> a new free account and ⚠**DO NOT** use paid account
1. Run *<a href="https://github.com/patchescamerababy/copilot2api/releases">Get-token</a>* and open this <a href="https://github.com/login/device">URL</a> to get long term token.
2. Open <a href="https://github.com/copilot">this</a> to enable copilot
3. Save this token and use in client app
4. Run the server <a href="https://github.com/patchescamerababy/copilot2api/releases/">app</a> or Docker

## Docker 

    docker pull patchescamera/copilot2api:latest # AOT compilation based on Graalvm
    docker run -d -p 8080:80 patchescamera/copilot2api:latest
    
#### OR JRE8 version

    docker pull patchescamera/copilot2api:java
    docker run -d -p 8080:80 patchescamera/copilot2api:java

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
         
## Build Rust based ***Get-Token*** on Debian/Ubuntu:
    sudo apt update
    sudo apt install -y libssl-dev pkg-config cargo
    cargo build --release

Run
     
    ./target/release/Get-Token
     
## Notice:
1. The program will automatically save the token to SQlite's token.db. Next time, if the client does not provide a token, a random token in tokens.db will be used.
2. Image input/generation is not supported.
3. All tokens passed in will be recorded in tokens.db. Do not pass in invalid tokens.
4. When `GET /v1/models` does not receive a valid Bearer request header, a random bearer in tokens.db will be used. If there is no record in tokens.db, the default JSON will be returned.
5. The o1 series models do not support streaming. If *stream* is true, this program only simulates
6. ⚠Please do not log in with a paid account. It is always **recommended** to log in with a **free account**. There is no guarantee that the account will not be banned.
7. It is **not recommended** to run on public network 

# Model list:

### format:
 
#### id -> default version(also could use that id)

## Chat
gpt-4o -> gpt-4o-2024-05-13

gpt-4-o-preview -> gpt-4o-2024-05-13

gpt-4o-2024-08-06 -> gpt-4o-2024-08-06

gpt-4 -> gpt-4-0613

gpt-4o-mini -> gpt-4o-mini-2024-07-18

gpt-4-0125-preview

gpt-3.5-turbo -> gpt-3.5-turbo-0613

o1 -> o1-2024-12-17

o1-mini -> o1-mini-2024-09-12

claude-3.5-sonnet
## Embeddings

text-embedding-ada-002

text-embedding-3-small-inference -> text-embedding-3-small
## 

### If this project is helpful, please give stars⭐
