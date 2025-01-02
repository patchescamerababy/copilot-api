use reqwest::Client;
use serde::{Deserialize, Serialize};
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
    ExpiredToken,
    NetworkError,
    OtherError(String),
}

#[derive(Debug, Deserialize)]
struct DeviceCodeResponse {
    device_code: String,
    user_code: String,
    verification_uri: String,
    interval: u64,
}

#[derive(Debug, Deserialize)]
struct AccessTokenResponse {
    access_token: Option<String>,
    error: Option<String>,
    error_description: Option<String>,
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn Error>> {
    dotenv::dotenv().ok();

    let client = configure_client().await?;

    // 获取设备代码信息
    let device_code_resp = match get_device_code(&client).await {
        Ok(resp) => resp,
        Err(e) => {
            eprintln!("获取设备代码失败: {:?}", e);
            prompt_exit();
            return Err(e);
        }
    };

    // 自动打开默认浏览器并指向验证URI
    if let Err(e) = open_browser_with_code(&device_code_resp) {
        eprintln!("无法打开浏览器");
    }

    println!(
        "如果浏览器未打开，请在浏览器中访问 {} 并输入 {} 以登录。",
        device_code_resp.verification_uri, device_code_resp.user_code
    );

    // 开始轮询获取访问令牌
    match poll_access_token(&client, &device_code_resp).await {
        Ok(token) => {
            println!("Token是:\n{}", token);
        }
        Err(e) => {
            eprintln!("获取Token失败: {:?}", e);
        }
    }

    // 提示用户按下任意键后退出
    prompt_exit();

    Ok(())
}

async fn configure_client() -> Result<Client, Box<dyn Error>> {
    let client_builder = Client::builder()
        .timeout(Duration::from_secs(10))
        .user_agent("Rust OAuth Device Flow");

    let client = client_builder.build()?;
    Ok(client)
}

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

fn open_browser_with_code(device_code_resp: &DeviceCodeResponse) -> Result<(), Box<dyn Error>> {
    let url = format!(
        "{}?user_code={}",
        device_code_resp.verification_uri, device_code_resp.user_code
    );
    webbrowser::open(&url)?;
    Ok(())
}

fn prompt_exit() {
    print!("按下 Enter 键以退出...");
    io::stdout().flush().unwrap();
    let mut buffer = String::new();
    let _ = io::stdin().read_line(&mut buffer);
}
