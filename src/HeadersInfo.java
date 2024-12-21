import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
public class HeadersInfo {
    private static final String HEX_CHARS = "0123456789abcdef";
    private static final SecureRandom secureRandom = new SecureRandom();
    private static final String uuid = UUID.randomUUID() + String.valueOf((long)(Math.random() * 1e13));
    private static final String vscode_machineid=generateRandomHex(64);
    public static final String openai_organization="github-copilot";
    public static final String editor_version="vscode/1.96.1";
    public static final String editor_plugin_version="copilot-chat/0.23.2";
    public static final String x_github_api_version="2024-12-15";
    public static final String content_type="application/json";
    public static final String user_agent="GitHubCopilotChat/0.23.2";

    /**
     * 构建请求头
     */
    public static Map<String, String> getCopilotHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Editor-Plugin-Version", HeadersInfo.editor_plugin_version);
        headers.put("Editor-Version", HeadersInfo.editor_version);
        headers.put("Openai-Organization", "github-copilot");
        headers.put("User-Agent", HeadersInfo.user_agent);
        headers.put("VScode-MachineId", HeadersInfo.vscode_machineid);
        headers.put("VScode-SessionId", HeadersInfo.uuid);
        headers.put("accept", "*/*");
        headers.put("Sec-Fetch-Site", "none");
        headers.put("Sec-Fetch-Mode", "no-cors");
        headers.put("Sec-Fetch-Dest", "empty");
        headers.put("accept-encoding", "gzip, deflate, br, zstd");
        headers.put("X-GitHub-Api-Version", HeadersInfo.x_github_api_version);
        headers.put("X-Request-Id", RandomXRequestID(32));
        return headers;
    }

    public static String generateRandomHex(int length) {
        StringBuilder sb = new StringBuilder(length);
        for(int i = 0; i < length; i++) {
            int index = secureRandom.nextInt(HEX_CHARS.length());
            sb.append(HEX_CHARS.charAt(index));
        }
        return sb.toString();
    }
    public static String RandomXRequestID(int length) {
        StringBuilder sb = new StringBuilder(32);
        Random random = new Random();

        // 生成32位的随机数字和小写字母
        for (int i = 0; i < length; i++) {
            int index = random.nextInt("abcdefghijklmnopqrstuvwxyz0123456789".length());
            sb.append("abcdefghijklmnopqrstuvwxyz0123456789".charAt(index));
        }

        // 格式化输出为xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx的形式
        return String.format("%s-%s-%s-%s-%s",
                sb.substring(0, 8),
                sb.substring(8, 12),
                sb.substring(12, 16),
                sb.substring(16, 20),
                sb.substring(20, 32));
    }
}
