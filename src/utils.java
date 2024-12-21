import com.sun.net.httpserver.HttpExchange;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.locks.ReentrantLock;

public class utils {
    private static final ReentrantLock tokenLock = new ReentrantLock();
    private static final TokenManager tokenManager = new TokenManager();
    public static String GetToken(String longTermToken) {

        // 创建 HttpClient 实例
        HttpClient client = HttpClient.newHttpClient();

        // 构建请求 URL
        String url = "https://api.github.com/copilot_internal/v2/token";

        // 创建 HttpRequest 并设置方法、URI 和头部
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .header("Authorization", "token " + longTermToken)
                .header("Editor-Plugin-Version", HeadersInfo.editor_plugin_version)
                .header("Editor-Version", HeadersInfo.editor_version)
                .header("User-Agent", HeadersInfo.user_agent)
                .header("x-github-api-version", HeadersInfo.x_github_api_version)
                .header("Sec-Fetch-Site", "none")
                .header("Sec-Fetch-Mode", "no-cors")
                .header("Sec-Fetch-Dest", "empty")
                .build();

        // 发送请求并处理响应
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            // 检查状态码是否在200到299之间
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                // 获取响应体
                String responseBody = response.body();

                // 解析 JSON 使用 org.json
                JSONObject jsonObject = new JSONObject(responseBody);

                // 提取 "token" 字段
                if (jsonObject.has("token")) {
                    String token = jsonObject.getString("token");
                    System.out.println("\n新Token:\n " + token);
                    return token;
                } else {
                    System.out.println("\"token\" 字段未找到在响应中。");
                }
            } else {
                System.out.println(longTermToken);
                System.out.println("请求失败，状态码: " + response.statusCode());
                System.out.println("响应体: " + response.body());
            }
            return null;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }


    }
    /**
     * 获取有效的短期令牌。如果不存在或已过期，则生成新的短期令牌并更新数据库。
     *
     * @param longTermToken 长期令牌
     * @return 有效的短期令牌
     * @throws IOException 如果在获取或更新令牌时发生错误
     */
    public static String getValidTempToken(String longTermToken) throws IOException {
        // 如果令牌不存在或已过期，获取锁以防止多线程竞争
        tokenLock.lock();
        try {
            // 重新检查，防止多线程环境下的竞争
            String tempToken = tokenManager.getTempToken(longTermToken);


            if (isTokenExpired(tempToken)) {
                System.out.println("Token已过期");
                // 生成新的短期令牌
                String newTempToken = utils.GetToken(longTermToken);
                if (newTempToken == null || newTempToken.isEmpty()) {
                    throw new IOException("无法生成新的临时令牌。");
                }
                long newExpiry = extractTimestamp(newTempToken);
                boolean updated = tokenManager.updateTempToken(longTermToken, newTempToken, newExpiry);
                if (!updated) {
                    throw new IOException("无法更新临时令牌。");
                }
                return newTempToken;
            }
            else{
                return tempToken;
            }

        } finally {
            tokenLock.unlock();
        }
    }

    /**
     * 发送错误响应
     */
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
        // 使用split方法以";"分割字符串
//        System.out.println(input);
        String[] splitArray = input.split(";");

        // 遍历分割后的数组
        for (String part : splitArray) {
            // 如果找到包含 "exp=" 的部分
            if (part.startsWith("exp=")) {
                return Integer.parseInt(part.substring(4));
            }
        }
        // 如果没有找到，返回一个默认值，比如0
        return 0;
    }

    /**
     * 检查 Token 是否过期
     *
     * @param token Token 字符串，格式如 "tid=b91081296b85fc09f76d3c4ac8f0a6a6;exp=1731950502"
     * @return 如果过期则返回 true，未过期返回 false
     */
    public static boolean isTokenExpired(String token) {
        int exp = extractTimestamp(token);
        long currentEpoch = Instant.now().getEpochSecond();
        // 格式化时间戳为 2024年xx月xx日xx时xx分xx秒
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒");

        // 格式化过期时间
        LocalDateTime expirationTime = LocalDateTime.ofInstant(Instant.ofEpochSecond(exp), ZoneId.systemDefault());
        String formattedExpiration = expirationTime.format(formatter);

        // 格式化当前时间
        LocalDateTime currentTime = LocalDateTime.ofInstant(Instant.ofEpochSecond(currentEpoch), ZoneId.systemDefault());
        String formattedCurrent = currentTime.format(formatter);

        // 计算剩余时间（分钟和秒）
        long remainingSeconds = exp - currentEpoch;
        long minutes = remainingSeconds / 60;
        long seconds = remainingSeconds % 60;

        // 打印结果
        System.out.println("当前时间: " + formattedCurrent);
        System.out.println("过期时间: " + formattedExpiration);
        System.out.println("还剩: " + minutes + "分钟" + seconds + "秒");
        return exp < currentEpoch;
    }
    public static String getToken(String authorizationHeader,HttpExchange exchange){
        final TokenManager tokenManager = new TokenManager(); // 确保 TokenManager 实例
        String longTermToken;
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            // 使用随机长期令牌
            longTermToken = tokenManager.getRandomLongTermToken();
            System.out.println("使用随机长期令牌: " + longTermToken);
        } else {
            // 提取长期令牌
            longTermToken = authorizationHeader.substring("Bearer ".length()).trim();
            if (longTermToken.isEmpty()) {
                sendError(exchange, "Token is empty.", 401);
                return null;
            }

            // 检查令牌前缀是否为 "ghu" 或 "gho"
            if (!(longTermToken.startsWith("ghu") || longTermToken.startsWith("gho"))) {
                utils.sendError(exchange, "Invalid token prefix.", 401);
                return null;
            }

            // 检查长期令牌是否存在于数据库
            if (!tokenManager.isLongTermTokenExists(longTermToken)) {
                // 如果不存在，添加它
                String newTempToken = utils.GetToken(longTermToken); // 生成新的临时令牌
                if (newTempToken == null || newTempToken.isEmpty()) {

                    return null;
                }
                long tempTokenExpiry = utils.extractTimestamp(newTempToken); // 假设临时令牌的过期时间可以通过此方法获取
                boolean added = tokenManager.addLongTermToken(longTermToken, newTempToken, tempTokenExpiry);
                if (!added) {
                    sendError(exchange, "无法添加长期令牌。", 500);
                    return null;
                }
            }
        }

        // 获取有效的短期令牌
        String tempToken;
        try {
            tempToken = utils.getValidTempToken(longTermToken);
        } catch (IOException e) {
            sendError(exchange, "令牌处理失败: " + e.getMessage(), 500);
            return null;
        }

        if (tempToken == null || tempToken.isEmpty()) {
            sendError(exchange, "无法获取有效的临时令牌。", 500);
            return null;
        }
        return tempToken;
    }
}
