import okhttp3.*;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

public class GitHubOAuthDeviceFlow {

    private static final String CLIENT_ID = "Iv1.b507a08c87ecfe98";
    private static final String DEVICE_CODE_URL = "https://github.com/login/device/code";
    private static final String ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");


    private static final Map<String, String> HEADERS = new HashMap<>();

    static {
        HEADERS.put("Accept", "application/json");
        HEADERS.put("Content-Type", "application/json");
    }


    enum LoginError {
        AUTH_PENDING,
        EXPIRED_TOKEN,
        NETWORK_ERROR,
        OTHER_ERROR,
        NONE
    }

    private final OkHttpClient client;

    public GitHubOAuthDeviceFlow(Map<String, String> proxies) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .readTimeout(java.time.Duration.ofSeconds(10));

        if (proxies != null && !proxies.isEmpty()) {
            Proxy proxy = null;
            String proxyUrl = proxies.get("http");
            if (proxyUrl != null && !proxyUrl.isEmpty()) {
                try {
                    java.net.URL url = new java.net.URL(proxyUrl);
                    String host = url.getHost();
                    int port = url.getPort() != -1 ? url.getPort() : 80;
                    proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port));
                } catch (Exception e) {
                    System.err.println("unavailable proxy URL: " + proxyUrl);
                }
            }
            if (proxy != null) {
                builder.proxy(proxy);
            }
        }

        client = builder.build();
    }

    /**
     * 从GitHub获取登录信息。
     *
     * @return 包含LoginError和响应的JSONObject或错误消息的Pair
     */
    public Pair<LoginError, Object> getLoginInfo() {
        JSONObject body = new JSONObject();
        body.put("client_id", CLIENT_ID);
        body.put("scope", "read:user");

        Request request = new Request.Builder()
                .url(DEVICE_CODE_URL)
                .headers(Headers.of(HEADERS))
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return new Pair<>(LoginError.OTHER_ERROR, "Unexpected response code: " + response.code());
            }
            String responseBody = null;
            if (response.body() != null) {
                responseBody = response.body().string();
            }
            JSONObject json = null;
            if (responseBody != null) {
                json = new JSONObject(responseBody);
            }
            return new Pair<>(LoginError.NONE, json);
        } catch (IOException e) {
            return new Pair<>(LoginError.NETWORK_ERROR, null);
        } catch (Exception e) {
            return new Pair<>(LoginError.OTHER_ERROR, e.getMessage());
        }
    }

    /**
     * 使用设备代码轮询GitHub以获取访问令牌。
     *
     * @param deviceCode 设备代码
     * @return 包含LoginError和访问令牌或错误消息的Pair
     */
    public Pair<LoginError, Object> pollAuth(String deviceCode) {
        JSONObject body = new JSONObject();
        body.put("client_id", CLIENT_ID);
        body.put("device_code", deviceCode);
        body.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");

        Request request = new Request.Builder()
                .url(ACCESS_TOKEN_URL)
                .headers(Headers.of(HEADERS))
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return new Pair<>(LoginError.OTHER_ERROR, "Unexpected response code: " + response.code());
            }
            String responseBody = null;
            if (response.body() != null) {
                responseBody = response.body().string();
            }
            JSONObject json = null;
            if (responseBody != null) {
                json = new JSONObject(responseBody);
            }

            if (json.has("error")) {
                String error = json.getString("error");
                switch (error) {
                    case "authorization_pending":
                        return new Pair<>(LoginError.AUTH_PENDING, null);
                    case "expired_token":
                        return new Pair<>(LoginError.EXPIRED_TOKEN, null);
                    default:
                        return new Pair<>(LoginError.OTHER_ERROR, error);
                }
            }

            if (json.has("access_token")) {
                return new Pair<>(LoginError.NONE, json.getString("access_token"));
            }

            return new Pair<>(LoginError.OTHER_ERROR, "Unknown error");
        } catch (IOException e) {
            return new Pair<>(LoginError.NETWORK_ERROR, null);
        } catch (Exception e) {
            return new Pair<>(LoginError.OTHER_ERROR, e.getMessage());
        }
    }

    /**
     * 执行OAuth设备授权流程以获取访问令牌。
     *
     * @return 包含LoginError和访问令牌或null的Pair
     */
    public Pair<LoginError, String> getToken() {
        Pair<LoginError, Object> loginInfoPair = getLoginInfo();
        if (loginInfoPair.getFirst() != LoginError.NONE) {
            LoginError error = loginInfoPair.getFirst();
            switch (error) {
                case NETWORK_ERROR:
                    System.err.println("Network error. Please check your network connection.");
                    break;
                case OTHER_ERROR:
                    System.err.println("Unknown error while getting login info.");
                    System.err.println(loginInfoPair.getSecond());
                    break;
                default:
                    System.err.println("Unexpected error: " + error);
            }
            return new Pair<>(error, null);
        }

        JSONObject loginInfo = (JSONObject) loginInfoPair.getSecond();
        int interval = loginInfo.getInt("interval");
        String verificationUri = loginInfo.getString("verification_uri");
        String userCode = loginInfo.getString("user_code");
        String deviceCode = loginInfo.getString("device_code");

        System.out.printf("Please open\n\n%s\n\nin your browser and enter code\n\n%s\n\nto log in.%n", verificationUri, userCode);

        while (true) {
            Pair<LoginError, Object> pollResult = pollAuth(deviceCode);
            if (pollResult.getFirst() == LoginError.NONE) {
                return new Pair<>(LoginError.NONE, (String) pollResult.getSecond());
            } else if (pollResult.getFirst() == LoginError.AUTH_PENDING) {
                // 继续轮询
            } else if (pollResult.getFirst() == LoginError.EXPIRED_TOKEN) {
                System.err.println("Token expired. Please restart the process.");
                return new Pair<>(LoginError.EXPIRED_TOKEN, null);
            } else if (pollResult.getFirst() == LoginError.NETWORK_ERROR) {
                System.err.println("Network error. Please check your network connection.");
                return new Pair<>(LoginError.NETWORK_ERROR, null);
            } else if (pollResult.getFirst() == LoginError.OTHER_ERROR) {
                System.err.println("Unknown error while polling for token.");
                System.err.println(pollResult.getSecond());
                return new Pair<>(LoginError.OTHER_ERROR, null);
            }

            try {
                Thread.sleep(interval * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Pair<>(LoginError.OTHER_ERROR, "Error while waiting for user input.");
            }
        }
    }

    public static void main(String[] args) {
        Map<String, String> proxies = new HashMap<>();
        proxies.put("http", getProxyEnv("HTTP_PROXY"));
        proxies.put("https", getProxyEnv("HTTPS_PROXY"));

        // 验证代理URL
        for (Map.Entry<String, String> entry : proxies.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (value != null && !value.isEmpty()) {
                if (!Pattern.matches("^.+://.+$", value)) {
                    proxies.put(key, "http://" + value);
                }
            } else {
                proxies.put(key, "");
            }
        }

        GitHubOAuthDeviceFlow oauthFlow = new GitHubOAuthDeviceFlow(proxies);
        Pair<LoginError, String> tokenPair = oauthFlow.getToken();

        if (tokenPair.getFirst() == LoginError.NONE) {
            System.out.println("Your Token: \n" + tokenPair.getSecond());
            System.out.println("Press Enter to exit.");
            try {
                if(System.in.read() == '\n') {
                    System.exit(0);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            System.err.println("Could not get token.");
        }
    }

    /**
     * 从环境变量中获取代理URL。
     *
     * @param envVar 环境变量名称
     * @return 代理URL或空字符串
     */
    private static String getProxyEnv(String envVar) {
        String value = System.getenv(envVar);
        if (value == null || value.isEmpty()) {
            value = System.getenv(envVar.toLowerCase());
        }
        return value != null ? value : "";
    }

    /**
     * 简单的泛型Pair类。
     *
     * @param <F> 第一个元素的类型
     * @param <S> 第二个元素的类型
     */
    static class Pair<F, S> {
        private final F first;
        private final S second;

        public Pair(F first, S second) {
            this.first = first;
            this.second = second;
        }

        public F getFirst() { return first; }
        public S getSecond() { return second; }
    }
}
