import com.sun.net.httpserver.HttpExchange;
import okhttp3.*;
import org.json.JSONObject;

import javax.net.ssl.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

public class utils {
    private static final ReentrantLock tokenLock = new ReentrantLock();
    private static final TokenManager tokenManager = new TokenManager();

    // OkHttp client instance
    public static OkHttpClient client = createOkHttpClient();

    public static OkHttpClient createOkHttpClient() {
        OkHttpClient okHttpClient1 = null;
        try {
            // 创建一个不验证证书的 TrustManager
            final X509TrustManager trustAllCertificates = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    // 不做任何检查，信任所有客户端证书
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    // 不做任何检查，信任所有服务器证书
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            };

            // 创建 SSLContext，使用我们的 TrustManager
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{trustAllCertificates}, null);

            // 创建代理
            SocketAddress proxyAddress = new InetSocketAddress("127.0.0.1", 5257);
            Proxy proxy = new Proxy(Proxy.Type.HTTP, proxyAddress);

            // 如果需要代理认证
//            Authenticator proxyAuthenticator = new Authenticator() {
//                @Override
//                public Request authenticate(Route route, Response response) throws IOException {
//                    String credential = Credentials.basic("username", "password");
//                    return response.request().newBuilder()
//                            .header("Proxy-Authorization", credential)
//                            .build();
//                }
//            };

            // 创建 OkHttpClient
            okHttpClient1 = new OkHttpClient.Builder()
                    .proxy(proxy)  // 设置代理
//                    .proxyAuthenticator(proxyAuthenticator)  // 如果需要代理认证
                    .sslSocketFactory(sslContext.getSocketFactory(), trustAllCertificates)  // 设置 SSL
                    .hostnameVerifier(new HostnameVerifier() {
                        @Override
                        public boolean verify(String hostname, SSLSession session) {
                            return true;  // 不验证主机名
                        }
                    })
                    .connectTimeout(30, TimeUnit.SECONDS)  // 连接超时
                    .readTimeout(30, TimeUnit.SECONDS)     // 读取超时
                    .writeTimeout(30, TimeUnit.SECONDS)    // 写入超时
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("OkHttpClient 初始化失败", e);
        }
        return okHttpClient1;
    }


    public static <jsonObject> String GetToken(String longTermToken) {
        try {
            Request request = new Request.Builder()
                    .url("https://api.github.com/copilot_internal/v2/token")
                    .addHeader("Authorization", "token " + longTermToken)
                    .addHeader("Editor-Plugin-Version", HeadersInfo.editor_plugin_version)
                    .addHeader("Editor-Version", HeadersInfo.editor_version)
                    .addHeader("User-Agent", HeadersInfo.user_agent)
                    .addHeader("x-github-api-version", HeadersInfo.x_github_api_version)
                    .addHeader("Sec-Fetch-Site", "none")
                    .addHeader("Sec-Fetch-Mode", "no-cors")
                    .addHeader("Sec-Fetch-Dest", "empty")
                    .get()
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    String responseBody = response.body().string();
                    JSONObject jsonObject = new JSONObject(responseBody);
                    if (jsonObject.has("token")) {
                        String token = jsonObject.getString("token");
                        System.out.println("\nNew Token:\n " + token);
                        if (jsonObject.has("endpoints")) {
                            JSONObject endpoints = jsonObject.getJSONObject("endpoints");
                            CompletionHandler.setCopilotChatCompletionsUrl(endpoints.getString("api") + "/chat/completions");
                            System.out.println("API: " + endpoints.getString("api"));

                        }
                        return token;
                    } else {
                        System.out.println("\"token\" field not found in the response.");
                    }
                } else {
                    String errorResponse = null;
                    if (response.body() != null) {
                        errorResponse = response.body().string();
                    }
                    System.out.println("Request failed, status code: " + response.code());
                    System.out.println("Response body: " + errorResponse);
                }
            }
            return null;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static String getValidTempToken(String longTermToken) throws IOException {
        tokenLock.lock();
        try {
            String tempToken = tokenManager.getTempToken(longTermToken);
            System.out.println("Login in as:" + tokenManager.getUsername(longTermToken));
            if (isTokenExpired(tempToken)) {
                System.out.println("Token has expired");
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
            e.printStackTrace();
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

            if (!(longTermToken.startsWith("ghu") || longTermToken.startsWith("gho"))) {
                utils.sendError(exchange, "Invalid token prefix.", 401);
                return null;
            }
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

            if (!tokenManager.isLongTermTokenExists(longTermToken)) {
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

    public static byte[] decodeImageData(String dataUrl) {
        try {
            String[] parts = dataUrl.split(",");
            if (parts.length != 2) {
                System.err.println("Invalid data URL format.");
                return null;
            }
            String base64Data = parts[1];
            return java.util.Base64.getDecoder().decode(base64Data);
        } catch (IllegalArgumentException e) {
            System.err.println("Base64 decode failed: " + e.getMessage());
            return null;
        }
    }

    public static byte[] downloadImageData(String imageUrl) {
        try {
            Request request = new Request.Builder()
                    .url(imageUrl)
                    .get()
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    System.err.println("Failed to download image, response code: " + response.code());
                    return null;
                }

                return response.body().bytes();
            }
        } catch (IOException e) {
            System.err.println("Failed to download image: " + e.getMessage());
            return null;
        }
    }
}
