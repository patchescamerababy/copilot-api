# copilot2api
Copilot to OpenAI API

## Supported APIs

- `GET /v1/models`: Get model list
- `POST /v1/chat/completions`: Chat API
- `POST /v1/embeddings`

## How To Use
1. Use Get-token to get your long term token.
2. Save this token and use in client app
3. Run the server <a href="https://github.com/patchescamerababy/copilot2api/">app</a>


## Notice:
1. The program will automatically save all received tokens to token.db
2. When `GET /v1/models` does not receive a valid token as an authorization request header, a default JSON is returned.
3. Claude-3.5-sonnet may have network disconnection issues

# Warning
1. Please do not log in with a paid account. It is always **recommended** to log in with a **free account**. There is no guarantee that the account will not be banned.
