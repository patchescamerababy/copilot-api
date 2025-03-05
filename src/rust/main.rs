use clipboard::{ClipboardContext, ClipboardProvider};
use reqwest::{Client, Proxy};
use serde::{Deserialize, Serialize};
use std::env;
use std::error::Error;
use std::io::{self, Write};
use std::time::Duration;
use tokio::time::sleep;
use webbrowser;

const CLIENT_ID: &str = "Iv1.b507a08c87ecfe98";
const DEVICE_CODE_URL: &str = "https://github.com/login/device/code";
const ACCESS_TOKEN_URL: &str = "https://github.com/login/oauth/access_token";

#[derive(Debug)]
enum LoginError {
    AuthPending,
    ExpiredToken,
    NetworkError,
    OtherError(String),
}

#[derive(Debug, Deserialize)]
struct DeviceCodeResponse {
    device_code: String,
    user_code: String,
    verification_uri: String,
    expires_in: u64,
    interval: u64,
}

#[derive(Debug, Deserialize)]
struct AccessTokenResponse {
    access_token: Option<String>,
    token_type: Option<String>,
    scope: Option<String>,
    error: Option<String>,
    error_description: Option<String>,
}

// 添加复制到剪贴板的辅助函数
fn copy_to_clipboard(text: &str) -> Result<(), Box<dyn Error>> {
    let mut ctx: ClipboardContext = ClipboardProvider::new()?;
    ctx.set_contents(text.to_owned())?;
    Ok(())
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn Error>> {
    dotenv::dotenv().ok();

    let client = configure_client().await?;

    // 获取设备代码信息
    let device_code_resp = match get_device_code(&client).await {
        Ok(resp) => {
            // 将设备码复制到剪贴板
            if let Err(e) = copy_to_clipboard(&resp.user_code) {
                eprintln!("无法复制设备码到剪贴板: {}", e);
            } else {
                println!("设备码已复制到剪贴板!");
            }
            resp
        }
        Err(e) => {
            eprintln!("获取设备代码失败: {:?}", e);
            prompt_exit();
            return Err(e);
        }
    };

    // 自动打开默认浏览器并指向验证URI
    if let Err(e) = open_browser_with_code(&device_code_resp) {
        eprintln!("无法打开浏览器: {}", e);
        eprintln!(
            "请手动在浏览器中打开 {} 并输入代码 {} 以登录。",
            device_code_resp.verification_uri, device_code_resp.user_code
        );
    }

    println!(
        "如果浏览器未自动打开，请在浏览器中访问 {} 并输入代码 {} 以登录。",
        device_code_resp.verification_uri, device_code_resp.user_code
    );

    // 开始轮询获取访问令牌
    match poll_access_token(&client, &device_code_resp).await {
        Ok(token) => {
            println!("Token是:\n{}", token);
            // 将token复制到剪贴板
            if let Err(e) = copy_to_clipboard(&token) {
                eprintln!("无法复制Token到剪贴板: {}", e);
            } else {
                println!("Token已复制到剪贴板!");
            }
        }
        Err(e) => {
            eprintln!("获取Token失败: {:?}", e);
        }
    }

    // 提示用户按下任意键后退出
    prompt_exit();

    Ok(())
}

/// 配置HTTP客户端，自动检测系统代理
async fn configure_client() -> Result<Client, Box<dyn Error>> {
    let client_builder = Client::builder()
        .timeout(Duration::from_secs(10))
        .user_agent("Rust OAuth Device Flow");

    // `reqwest` 默认会自动检测系统代理设置，无需手动配置
    let client = client_builder.build()?;
    Ok(client)
}

/// 获取设备代码信息
async fn get_device_code(client: &Client) -> Result<DeviceCodeResponse, Box<dyn Error>> {
    #[derive(Serialize)]
    struct DeviceCodeRequest<'a> {
        client_id: &'a str,
        scope: &'a str,
    }

    let request_body = DeviceCodeRequest {
        client_id: CLIENT_ID,
        scope: "read:user",
    };

    let resp = client
        .post(DEVICE_CODE_URL)
        .header("Accept", "application/json")
        .json(&request_body)
        .send()
        .await;

    match resp {
        Ok(r) => {
            if r.status().is_success() {
                let device_code_resp: DeviceCodeResponse = r.json().await?;
                Ok(device_code_resp)
            } else {
                let status = r.status();
                let text = r.text().await.unwrap_or_default();
                Err(format!("Unexpected response {}: {}", status, text).into())
            }
        }
        Err(e) => Err(format!("Network error: {}", e).into()),
    }
}

/// 轮询获取访问令牌
async fn poll_access_token(
    client: &Client,
    device_code_resp: &DeviceCodeResponse,
) -> Result<String, LoginError> {
    #[derive(Serialize)]
    struct AccessTokenRequest<'a> {
        client_id: &'a str,
        device_code: &'a str,
        grant_type: &'a str,
    }

    let request_body = AccessTokenRequest {
        client_id: CLIENT_ID,
        device_code: &device_code_resp.device_code,
        grant_type: "urn:ietf:params:oauth:grant-type:device_code",
    };

    let interval = device_code_resp.interval;

    loop {
        let resp = client
            .post(ACCESS_TOKEN_URL)
            .header("Accept", "application/json")
            .json(&request_body)
            .send()
            .await;

        match resp {
            Ok(r) => {
                if r.status().is_success() {
                    match r.json::<AccessTokenResponse>().await {
                        Ok(token_resp) => {
                            if let Some(access_token) = token_resp.access_token {
                                return Ok(access_token);
                            } else if let Some(error) = token_resp.error {
                                match error.as_str() {
                                    "authorization_pending" => {
                                        // 继续轮询
                                    }
                                    "expired_token" => {
                                        return Err(LoginError::ExpiredToken);
                                    }
                                    _ => {
                                        let desc = token_resp
                                            .error_description
                                            .unwrap_or_else(|| "No description".to_string());
                                        return Err(LoginError::OtherError(desc));
                                    }
                                }
                            } else {
                                return Err(LoginError::OtherError(
                                    "Unknown error response".to_string(),
                                ));
                            }
                        }
                        Err(e) => {
                            return Err(LoginError::OtherError(format!(
                                "Failed to parse JSON: {}",
                                e
                            )));
                        }
                    }
                } else {
                    let status = r.status();
                    let text = r.text().await.unwrap_or_default();
                    return Err(LoginError::OtherError(format!(
                        "Unexpected response {}: {}",
                        status, text
                    )));
                }
            }
            Err(e) => {
                eprintln!("网络错误: {}", e);
                return Err(LoginError::NetworkError);
            }
        }

        // 等待指定的间隔时间后再次轮询
        sleep(Duration::from_secs(interval)).await;
    }
}

/// 自动打开默认浏览器并指向验证URI
fn open_browser_with_code(device_code_resp: &DeviceCodeResponse) -> Result<(), Box<dyn Error>> {
    let url = format!(
        "{}?user_code={}",
        device_code_resp.verification_uri, device_code_resp.user_code
    );
    webbrowser::open(&url)?;
    Ok(())
}

/// 提示用户按下Enter后退出
fn prompt_exit() {
    print!("按下 Enter 键以退出...");
    io::stdout().flush().unwrap();
    let mut buffer = String::new();
    let _ = io::stdin().read_line(&mut buffer);
}
