package com.example.demo.util;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;

import okhttp3.*;
import org.json.JSONObject;

import javax.net.ssl.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

public class Utils {
    private static final ReentrantLock tokenLock = new ReentrantLock();
    private static final TokenManager tokenManager = new TokenManager();
    public static OkHttpClient client = createOkHttpClient();

    public static OkHttpClient getOkHttpClient(){
        return client;
    }

    private static OkHttpClient createOkHttpClient(){
        OkHttpClient okHttpClient1 = null;
        try {
            final X509TrustManager trustAllCertificates = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException { }
                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException { }
                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{trustAllCertificates}, null);
//            Proxy proxy = getSystemProxy();
            okHttpClient1 = new OkHttpClient.Builder()
//                    .proxy(proxy)
                    .sslSocketFactory(sslContext.getSocketFactory(), trustAllCertificates)
                    .hostnameVerifier((hostname, session) -> true)
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .build();
        } catch(Exception e){
            throw new RuntimeException("OkHttpClient initialization failed", e);
        }
        return okHttpClient1;
    }

//    public static Proxy getSystemProxy(){
//        String os = System.getProperty("os.name").toLowerCase();
//        Proxy proxy = Proxy.NO_PROXY;
//        try {
//            if(os.contains("win")){
//                String proxyHost = System.getProperty("http.proxyHost");
//                String proxyPort = System.getProperty("http.proxyPort");
//                if(proxyHost != null && proxyPort != null){
//                    try{
//                        int port = Integer.parseInt(proxyPort);
//                        if(port > 0 && port <= 65535){
//                            return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, port));
//                        }
//                    } catch(NumberFormatException e){
//                        System.err.println("Invalid proxy port number: " + proxyPort);
//                    }
//                }
//                String envProxy = System.getenv("HTTP_PROXY");
//                if(envProxy != null && !envProxy.isEmpty()){
//                    try{
//                        return parseProxyFromString(envProxy);
//                    } catch(Exception e){
//                        System.err.println("Failed to parse HTTP_PROXY: " + e.getMessage());
//                    }
//                }
//                try{
//                    boolean proxyEnable = Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER,
//                            "Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings", "ProxyEnable") != 0;
//                    if(proxyEnable){
//                        String proxyServer = Advapi32Util.registryGetStringValue(
//                                WinReg.HKEY_CURRENT_USER,
//                                "Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings",
//                                "ProxyServer");
//                        if(proxyServer != null && !proxyServer.isEmpty()){
//                            if(proxyServer.contains("=")){
//                                for(String proxy0: proxyServer.split(";")){
//                                    if(proxy0.startsWith("http=")){
//                                        proxyServer = proxy0.substring(5);
//                                        break;
//                                    }
//                                }
//                            }
//                            System.out.println("Detected system proxy: " + proxyServer);
//                            return parseProxyFromString(proxyServer);
//                        }
//                    }
//                } catch(Exception e){
//                    System.err.println("Failed to read Windows registry: " + e.getMessage());
//                }
//            } else if(os.contains("nix") || os.contains("nux") || os.contains("mac")){
//                String[] proxyEnvVars = {"https_proxy", "HTTPS_PROXY", "http_proxy", "HTTP_PROXY"};
//                for(String envVar: proxyEnvVars){
//                    String proxyUrl = System.getenv(envVar);
//                    if(proxyUrl != null && !proxyUrl.isEmpty()){
//                        try{
//                            System.out.println("Detected system proxy: " + proxyUrl);
//                            return parseProxyFromString(proxyUrl);
//                        } catch(Exception e){
//                            System.err.println("Failed to parse " + envVar + ": " + e.getMessage());
//                        }
//                    }
//                }
//            } else {
//                System.out.println("Unknown OS or no system proxy configuration found.");
//            }
//        } catch(Exception e){
//            System.err.println("Error while getting system proxy: " + e.getMessage());
//        }
//        return proxy;
//    }

    private static Proxy parseProxyFromString(String proxyString){
        proxyString = proxyString.trim().toLowerCase();
        proxyString = proxyString.replaceFirst("^(http|https)://", "");
        if(proxyString.contains("@")){
            proxyString = proxyString.substring(proxyString.lastIndexOf("@") + 1);
        }
        String host;
        int port;
        if(proxyString.contains(":")){
            String[] parts = proxyString.split(":");
            host = parts[0];
            String portStr = parts[1].split("/")[0];
            port = Integer.parseInt(portStr);
        } else {
            host = proxyString;
            port = 80;
        }
        if(!host.isEmpty() && port > 0 && port <= 65535){
            return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port));
        }
        throw new IllegalArgumentException("Invalid proxy configuration");
    }

    public static String GetToken(String longTermToken){
        try{
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
            try (Response response = client.newCall(request).execute()){
                if(response.isSuccessful()){
                    String responseBody = response.body().string();
                    JSONObject jsonObject = new JSONObject(responseBody);
                    if(jsonObject.has("token")){
                        String token = jsonObject.getString("token");
                        System.out.println("\nNew Token:\n " + token);
                        if(jsonObject.has("endpoints")){
                            JSONObject endpoints = jsonObject.getJSONObject("endpoints");
                            System.out.println("API: " + endpoints.getString("api"));
                        }
                        return token;
                    } else {
                        System.out.println("\"token\" field not found in response.");
                    }
                } else {
                    String errorResponse = response.body() != null ? response.body().string() : "";
                    System.out.println("Request failed, status code: " + response.code());
                    System.out.println("Response body: " + errorResponse);
                }
            }
            return null;
        } catch(Exception e){
            e.printStackTrace();
            return null;
        }
    }

    public static String getValidTempToken(String longTermToken) throws IOException {
        tokenLock.lock();
        try{
            String tempToken = tokenManager.getTempToken(longTermToken);
            System.out.println("Login in as:" + tokenManager.getUsername(longTermToken));
            if(isTokenExpired(tempToken)){
                System.out.println("Token has expired");
                String newTempToken = GetToken(longTermToken);
                if(newTempToken == null || newTempToken.isEmpty()){
                    throw new IOException("Unable to generate a new temporary token.");
                }
                int newExpiry = extractTimestamp(newTempToken);
                boolean updated = tokenManager.updateTempToken(longTermToken, newTempToken, newExpiry);
                if(!updated){
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

    public static int extractTimestamp(String input){
        String[] splitArray = input.split(";");
        for(String part : splitArray){
            if(part.startsWith("exp=")){
                return Integer.parseInt(part.substring(4));
            }
        }
        return 0;
    }

    public static boolean isTokenExpired(String token){
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

    // 为 Spring 重载的 getToken（不使用 HttpExchange）
    public static String getToken(String authorizationHeader) {
        String longTermToken;
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            longTermToken = tokenManager.getRandomLongTermToken();
            System.out.println("Using random long-term token: " + longTermToken);
        } else {
            longTermToken = authorizationHeader.substring("Bearer ".length()).trim();
            if (longTermToken.isEmpty()) {
                return null;
            }
            if (!(longTermToken.startsWith("ghu") || longTermToken.startsWith("gho"))) {
                return null;
            }
            AtomicReference<String> login = new AtomicReference<>("");
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
                    try (Response response = client.newCall(request).execute()) {
                        if (response.isSuccessful()) {
                            String responseBody = response.body() != null ? response.body().string() : "";
                            JSONObject jsonObject = new JSONObject(responseBody);
                            if (jsonObject.has("login")) {
                                login.set(jsonObject.getString("login"));
                                System.out.println("\nlogin as: " + login.get());
                            }
                        } else {
                            String errorResponse = response.body() != null ? response.body().string() : "";
                            System.out.println("Request failed, status code: " + response.code());
                            System.out.println("Response body: " + errorResponse);
                        }
                    }
                } catch (IOException ioException) {
                    ioException.printStackTrace();
                }
            }, Executors.newSingleThreadScheduledExecutor());
            if (!tokenManager.isLongTermTokenExists(longTermToken)) {
                String newTempToken = GetToken(longTermToken);
                if (newTempToken == null || newTempToken.isEmpty()) {
                    return null;
                }
                int tempTokenExpiry = extractTimestamp(newTempToken);
                boolean added = tokenManager.addLongTermToken(longTermToken, newTempToken, tempTokenExpiry, login.get());
                if (!added) {
                    return null;
                }
            }
        }
        String tempToken;
        try {
            tempToken = getValidTempToken(longTermToken);
        } catch (IOException e) {
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
            Request request = new Request.Builder().url(imageUrl).get().build();
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
