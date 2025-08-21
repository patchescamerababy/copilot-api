//import com.sun.jna.platform.win32.Advapi32Util;
//import com.sun.jna.platform.win32.WinReg;
import com.sun.net.httpserver.HttpExchange;
import okhttp3.*;
import org.json.JSONObject;

import javax.net.ssl.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

public class utils {
    private static final ReentrantLock tokenLock = new ReentrantLock();
    private static final TokenManager tokenManager = new TokenManager();

    // OkHttp client instance
    public static OkHttpClient client = createOkHttpClient();

    public static OkHttpClient getOkHttpClient() {
        return createOkHttpClient();
    }
    private static OkHttpClient createOkHttpClient() {
        OkHttpClient okHttpClient = null;
        try {
            // 获取默认的受信任的证书存储
            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init((KeyStore) null);

            // 提取默认 TrustManager
            X509TrustManager defaultTrustManager = (X509TrustManager) trustManagerFactory.getTrustManagers()[0];

            // 创建一个不验证证书的 TrustManager
            final X509TrustManager trustAllCertificates = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {

                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {

                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            };

            // 创建 SSLContext，使用我们的 TrustManager
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{trustAllCertificates}, null);


            Proxy proxy = getSystemProxy();

            // 创建 OkHttpClient
            okHttpClient = new OkHttpClient.Builder()
                    .proxy(proxy)  // 设置代理
                    .sslSocketFactory(sslContext.getSocketFactory(), trustAllCertificates)  // 设置 SSL
                    .hostnameVerifier((hostname, session) -> {
                        return true;  // 不验证主机名
                    })
                    .connectTimeout(600, TimeUnit.SECONDS)  // 连接超时
                    .readTimeout(600, TimeUnit.SECONDS)     // 读取超时
                    .writeTimeout(600, TimeUnit.SECONDS)    // 写入超时
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("OkHttpClient 初始化失败", e);
        }
        return okHttpClient;
    }

    /**
     * 调用 reg.exe 读取注册表中某个键值（以字符串形式返回）。
     * 兼容 Windows XP 及以上。
     *
     * @param hive  根键名："HKCU", "HKLM" 等
     * @param path  子路径，例如 "Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"
     * @param key   值名称，例如 "ProxyEnable" 或 "ProxyServer"
     * @return      如果存在则返回值（例如 "0x1"、"proxy.example.com:8080" 等），否则返回 null
     */
    public static String readRegistry(String hive, String path, String key) throws IOException {
        ProcessBuilder pb = new ProcessBuilder("reg", "query",
                hive + "\\" + path,
                "/v", key);
        Process process = pb.start();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), "GBK"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith(key)) {
                    // 按空白分割，最后一段即为值
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 3) {
                        return parts[parts.length - 1];
                    }
                }
            }
        }
        return null;
    }

    /**
     * 解析代理字符串，支持：
     *  - socks=host:port 或 socks5=host:port
     *  - http=host:port、https=host:port
     *  - 纯 host:port（默认 HTTP，端口若缺省则用 80）
     *
     * @param proxyStr  原始代理配置字符串
     * @return          java.net.Proxy 对象（Type 为 HTTP 或 SOCKS）
     */
    private static Proxy parseProxy(String proxyStr) {
        String s = proxyStr.trim();

        // 去掉协议前缀（http://、https://、socks://、socks5://）
        s = s.replaceFirst("(?i)^(http|https|socks5?)://", "");

        // 去掉用户认证信息
        int at = s.lastIndexOf('@');
        if (at >= 0) {
            s = s.substring(at + 1);
        }

        // 默认 HTTP
        Proxy.Type type = Proxy.Type.HTTP;

        // 检查多协议条目形式：socks=...;http=...;...
        if (s.contains("=") && s.contains(";")) {
            // 以分号拆分，优先找 socks= 或 socks5=
            for (String entry : s.split(";")) {
                String e = entry.trim().toLowerCase();
                if (e.startsWith("socks5=") || e.startsWith("socks=")) {
                    type = Proxy.Type.SOCKS;
                    s = entry.substring(entry.indexOf('=') + 1);
                    break;
                } else if (e.startsWith("http=")) {
                    // 后续若无 socks，才处理 http=
                    s = entry.substring(entry.indexOf('=') + 1);
                    type = Proxy.Type.HTTP;
                }
            }
        } else {
            // 单一条目且以 socks= 或 socks5= 开头
            String low = s.toLowerCase();
            if (low.startsWith("socks5=") || low.startsWith("socks=")) {
                type = Proxy.Type.SOCKS;
                s = s.substring(s.indexOf('=') + 1);
            }
        }

        // 拆分 host:port
        String host;
        int port = (type == Proxy.Type.SOCKS ? 1080 : 80);
        if (s.contains(":")) {
            String[] hp = s.split(":", 2);
            host = hp[0];
            try {
                port = Integer.parseInt(hp[1].replaceAll("/.*$", ""));
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("无效的端口号: " + hp[1], ex);
            }
        } else {
            host = s;
        }

        return new Proxy(type, new InetSocketAddress(host, port));
    }

    /**
     * 获取 Windows 上的系统代理（HTTP / HTTPS / SOCKS5）。
     * 优先级：
     *   1. Java 系统属性 http.proxyHost/http.proxyPort
     *   2. 环境变量 HTTP_PROXY
     *   3. 注册表：ProxyEnable + ProxyServer
     */
    public static Proxy getWindowsProxy() {
        // 1. Java 系统属性
        String propHost = System.getProperty("http.proxyHost");
        String propPort = System.getProperty("http.proxyPort");
        if (propHost != null && propPort != null) {
            try {
                int port = Integer.parseInt(propPort);
                if (port > 0 && port <= 65535) {
                    return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(propHost, port));
                }
            } catch (NumberFormatException ignored) { }
        }

        // 2. 环境变量
        String env = System.getenv("HTTP_PROXY");
        if (env != null && !env.isEmpty()) {
            return parseProxy(env);
        }

        // 3. 注册表
        String hive = "HKCU";
        String path = "Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings";
        try {
            String enable = readRegistry(hive, path, "ProxyEnable");
            if ("0x1".equalsIgnoreCase(enable) || "1".equals(enable)) {
                String server = readRegistry(hive, path, "ProxyServer");
                if (server != null && !server.isEmpty()) {
                    System.out.println("Detected system proxy from Registry: " + server);
                    return parseProxy(server);
                }
            }
        } catch (IOException e) {
            System.err.println("Read Registry Failed: " + e.getMessage());
        }

        return Proxy.NO_PROXY;
    }

    /**
     * 获取 Unix-like (Linux/macOS) 系统代理（HTTP / HTTPS / SOCKS5）。
     * 检查环境变量（优先级由上至下）：
     *   socks5_proxy, SOCKS5_PROXY,
     *   socks_proxy,  SOCKS_PROXY,
     *   all_proxy,    ALL_PROXY,
     *   https_proxy,  HTTPS_PROXY,
     *   http_proxy,   HTTP_PROXY
     */
    public static Proxy getUnixProxy() {
        String[] vars = {
                "socks5_proxy", "SOCKS5_PROXY",
                "socks_proxy",  "SOCKS_PROXY",
                "all_proxy",    "ALL_PROXY",
                "https_proxy",  "HTTPS_PROXY",
                "http_proxy",   "HTTP_PROXY"
        };
        for (String env : vars) {
            String val = System.getenv(env);
            if (val != null && !val.isEmpty()) {
                return parseProxy(val);
            }
        }
        return Proxy.NO_PROXY;
    }

    /**
     * 检测当前操作系统并返回对应的系统代理设置。
     */
    public static Proxy getSystemProxy() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return getWindowsProxy();
        } else {
            return getUnixProxy();
        }
    }

    public static String GetToken(String longTermToken) {
        try {
            Request request = new Request.Builder()
                    .url("https://api.github.com/copilot_internal/v2/token")
                    .addHeader("Connection", "keep-alive")
                    .addHeader("authorization", "Bearer " + longTermToken)
                    .addHeader("sec-ch-ua-platform", "\"Windows\"")
                    .addHeader("Accept", "*/*")
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Code/1.102.3 Chrome/134.0.6998.205 Electron/35.6.0 Safari/537.36")
                    .addHeader("sec-ch-ua", "\"Not:A-Brand\";v=\"24\", \"Chromium\";v=\"134\"")
                    .addHeader("sec-ch-ua-mobile", "?0")
                    .addHeader("Origin", "vscode-file://vscode-app")
                    .addHeader("Sec-Fetch-Site", "cross-site")
                    .addHeader("Sec-Fetch-Mode", "cors")
                    .addHeader("Sec-Fetch-Dest", "empty")
                    .addHeader("Accept-Encoding", "gzip, deflate, br, zstd")
                    .addHeader("Accept-Language", "zh-CN")
                    .get()
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String responseBody = decompressResponse(response);

                    if (responseBody == null || responseBody.isEmpty()) {
                        System.out.println("Empty response body");
                        return null;
                    }

                    if (!responseBody.startsWith("{")) {
                        System.out.println("Response is not JSON format: " + responseBody);
                        return null;
                    }

                    JSONObject jsonObject = new JSONObject(responseBody);

                    if (jsonObject.has("token")) {
                        String token = jsonObject.getString("token");
                        System.out.println("\nNew Token:\n " + token);

                        if (jsonObject.has("endpoints")) {
                            JSONObject endpoints = jsonObject.getJSONObject("endpoints");
                            CompletionHandler.setCopilotChatCompletionsUrl(endpoints.getString("api") + "/chat/completions");
                        }
                        return token;
                    } else {
                        System.out.println("\"token\" field not found in the response.");
                    }
                } else {
                    String errorResponse = null;
                    if (response.body() != null) {
                        errorResponse = decompressResponse(response);
                    }
                    System.out.println("Request failed, status code: " + response.code());
                    System.out.println("Response body: " + errorResponse);
                }
            }
            return null;
        } catch (Exception e) {
            System.out.println("Cannot get token: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 根据 Content-Encoding 头解压响应体
     */
    private static String decompressResponse(Response response) throws IOException {
        String contentEncoding = response.header("Content-Encoding");
        InputStream inputStream = response.body().byteStream();

        try {
            // 根据压缩格式选择解压方式
            if (contentEncoding != null) {
                contentEncoding = contentEncoding.toLowerCase().trim();

                switch (contentEncoding) {
                    case "gzip":
                        inputStream = new GZIPInputStream(inputStream);
                        break;

                    case "deflate":
                        inputStream = new InflaterInputStream(inputStream);
                        break;

                    default:
                        System.err.println("Unknown compression format: " + contentEncoding);
                        break;
                }
            } else {
                System.out.println("No Content-Encoding header, assuming uncompressed");
            }

            // 读取解压后的内容
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }

                // 移除最后一个换行符
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
                    sb.setLength(sb.length() - 1);
                }

                return sb.toString();
            }
        } finally {
            inputStream.close();
        }
    }

    public static String getValidTempToken(String longTermToken) throws IOException {
        tokenLock.lock();
        try {
            String tempToken = tokenManager.getTempToken(longTermToken);
            System.out.println("\n\nLogin in as:" + tokenManager.getUsername(longTermToken));
            if (isTokenExpired(tempToken)) {
                String newTempToken = utils.GetToken(longTermToken);
                if (newTempToken == null || newTempToken.isEmpty()) {
                    throw new IOException("Unable to generate a new temporary token.");
                }
                int newExpiry = extractTimestamp(newTempToken);
                boolean updated = tokenManager.updateTempToken(longTermToken, newTempToken, newExpiry);
                if (!updated) {
                    throw new IOException("Unable to update temporary token.");
                }
                return newTempToken;
            } else {
                return tempToken;
            }
        } finally {
            tokenLock.unlock();
        }
    }

    public static void sendError(HttpExchange exchange, String message, int HTTP_code) {
        try {
            JSONObject error = new JSONObject();
            error.put("error", message);
            byte[] bytes = error.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(HTTP_code, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } catch (IOException e) {
            System.out.println("headers already sent");
        }
    }

    public static int extractTimestamp(String input) {
        String[] splitArray = input.split(";");
        for (String part : splitArray) {
            if (part.startsWith("exp=")) {
                return Integer.parseInt(part.substring(4));
            }
        }
        return 0;
    }

    public static boolean isTokenExpired(String token) {
        int exp = extractTimestamp(token);
        int currentEpoch = (int) Instant.now().getEpochSecond();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");

        LocalDateTime expirationTime = LocalDateTime.ofInstant(Instant.ofEpochSecond(exp), ZoneId.systemDefault());
        String formattedExpiration = expirationTime.format(formatter);

        LocalDateTime currentTime = LocalDateTime.ofInstant(Instant.ofEpochSecond(currentEpoch), ZoneId.systemDefault());
        String formattedCurrent = currentTime.format(formatter);

        int remainingSeconds = exp - currentEpoch;
        int minutes = remainingSeconds / 60;
        int seconds = remainingSeconds % 60;

        System.out.println("\n   Current epoch: " + currentEpoch);
        System.out.println("Expiration epoch: " + exp);
        System.out.println("  Current  time: " + formattedCurrent);
        System.out.println("Expiration time: " + formattedExpiration);
        System.out.println("Remaining: " + minutes + " minutes " + seconds + " seconds");
        return exp < currentEpoch;
    }

    public static String getToken(String authorizationHeader, HttpExchange exchange) {
        String longTermToken;
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            longTermToken = tokenManager.getRandomLongTermToken();
            System.out.println("Using random long-term token: " + longTermToken);
        } else {
            longTermToken = authorizationHeader.substring("Bearer ".length()).trim();
            if (longTermToken.isEmpty()) {
                sendError(exchange, "Token is empty.", 401);
                return null;
            }


            if (!tokenManager.isLongTermTokenExists(longTermToken)) {
                AtomicReference<String> login= new AtomicReference<>("");
                CompletableFuture.runAsync(() -> {
                    try {
                        Request request = new Request.Builder()
                                .url("https://api.github.com/user")
                                .addHeader("Authorization", "Bearer " + longTermToken)
                                .addHeader("Accept", "application/vnd.github+json")
                                .addHeader("Editor-Version", HeadersInfo.editor_version)
                                .addHeader("user-agent", HeadersInfo.user_agent)
                                .addHeader("x-github-api-version", "2022-11-28")
                                .addHeader("Sec-Fetch-Site", "none")
                                .addHeader("Sec-Fetch-Mode", "no-cors")
                                .addHeader("Sec-Fetch-Dest", "empty")
                                .get()
                                .build();


                        Response response = client.newCall(request).execute();
                        if (response.isSuccessful()) {
                            String responseBody = null;
                            if (response.body() != null) {
                                responseBody = response.body().string();
                            }
                            JSONObject jsonObject = null;
                            if (responseBody != null) {
                                jsonObject = new JSONObject(responseBody);
                            }
                            if (jsonObject != null && jsonObject.has("login")) {
                                login.set(jsonObject.getString("login"));
                                System.out.println("\nlogin as: " + login);
                            }
                        } else {
                            String errorResponse = null;
                            if (response.body() != null) {
                                errorResponse = response.body().string();
                            }
                            System.out.println("Request failed, status code: " + response.code());
                            System.out.println("Response body: " + errorResponse);
                        }
                    } catch (IOException ioException) {
                        ioException.printStackTrace();
                    }
                }, Executors.newSingleThreadScheduledExecutor());
                String newTempToken = utils.GetToken(longTermToken);
                if (newTempToken == null || newTempToken.isEmpty()) {
                    sendError(exchange, "Unable to generate a new temporary token.", 500);
                    return null;
                }
                int tempTokenExpiry = utils.extractTimestamp(newTempToken);
                boolean added = tokenManager.addLongTermToken(longTermToken, newTempToken, tempTokenExpiry, String.valueOf(login));
                if (!added) {
                    sendError(exchange, "Unable to add long-term token.", 500);
                    return null;
                }
            }
        }

        String tempToken;
        try {
            tempToken = utils.getValidTempToken(longTermToken);
        } catch (IOException e) {
            sendError(exchange, "Token processing failed: " + e.getMessage(), 500);
            return null;
        }

        if (tempToken == null || tempToken.isEmpty()) {
            sendError(exchange, "Unable to obtain a valid temporary token.", 500);
            return null;
        }
        return tempToken;
    }

    /**
     * 使用 OkHttp 发起 POST 请求并返回 Response
     */
    public static Response executeOkHttpRequest(Map<String, String> headers, JSONObject jsonBody, String url) throws IOException {
        // 构造 RequestBody
        RequestBody body = RequestBody.create(
                jsonBody.toString(),
                MediaType.parse("application/json; charset=utf-8")
        );

        // 构造请求
        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .post(body);

        // 设置请求头
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            requestBuilder.addHeader(entry.getKey(), entry.getValue());
        }

        Request request = requestBuilder.build();

        // 发送请求并返回响应
        return utils.getOkHttpClient().newCall(request).execute();
    }
}
