import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public class HeadersInfo {
    private static final String HEX_CHARS = "0123456789abcdef";
    private static final SecureRandom secureRandom = new SecureRandom();
    public static final String VScode_SessionId = UUID.randomUUID() + String.valueOf((long)(Math.random() * 1e13));
    public static final String vscode_machineid = generateRandomHex(64);
    public static final String openai_organization = "github-copilot";
    public static final String editor_version = "vscode/1.98.0-insider";
    public static final String copilot_language_server_version = "0.23.2";
    public static final String editor_plugin_version = "copilot-chat/"+copilot_language_server_version;




    public static final String x_github_api_version = "2025-01-21";
    public static final String content_type = "application/json";
    public static final String user_agent = "GitHubCopilotChat/"+copilot_language_server_version;

    /**
     * Build request headers
     */
    public static Map<String, String> getCopilotHeaders(String longTermToken) {
        // 先从 DB 读取 machine_id
        TokenManager tokenManager = new TokenManager();
        String machineId = null;

        try {
            machineId = tokenManager.getMachineId(longTermToken);
            if (machineId == null) {
                // 不存在则生成并尝试写入
                machineId = generateRandomHex(64);
                boolean saved = tokenManager.setMachineId(longTermToken, machineId);
                if (!saved) {
                    System.out.println("Warning: failed to save new machine_id, using generated one.");
                }
            }
        } catch (Exception e) {
            // 读取或写入失败，退回到新生成的
            System.out.println("Error accessing machine_id in DB: " + e.getMessage());
            machineId = generateRandomHex(64);
        }
        // 生成一个 sessionId（每次启动不同）
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", content_type);
        headers.put("Connection", "keep-alive");
        headers.put("Editor-Plugin-Version", editor_plugin_version);
        headers.put("copilot-integration-id","vscode-chat");
        headers.put("Editor-Version", editor_version);
        headers.put("Openai-Organization", openai_organization);
        headers.put("User-Agent", user_agent);
        headers.put("VScode-MachineId", machineId);
        headers.put("VScode-SessionId", VScode_SessionId);
        headers.put("accept", "*/*");
        headers.put("Sec-Fetch-Site", "none");
        headers.put("Sec-Fetch-Mode", "no-cors");
        headers.put("Sec-Fetch-Dest", "empty");
        headers.put("accept-encoding", "gzip, deflate, br, zstd");
        headers.put("X-GitHub-Api-Version", x_github_api_version);
        headers.put("X-Request-Id", RandomXRequestID(32));
        return headers;
    }

    public static String generateRandomHex(int length) {
        TokenManager tokenManager = new TokenManager();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            int index = secureRandom.nextInt(HEX_CHARS.length());
            sb.append(HEX_CHARS.charAt(index));
        }
        return sb.toString();
    }

    public static String RandomXRequestID(int length) {
        StringBuilder sb = new StringBuilder(32);
        Random random = new Random();

        // Generate a 32-character random string consisting of digits and lowercase letters
        for (int i = 0; i < length; i++) {
            int index = random.nextInt("abcdefghijklmnopqrstuvwxyz0123456789".length());
            sb.append("abcdefghijklmnopqrstuvwxyz0123456789".charAt(index));
        }

        // Format the output as xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
        String x = String.format("%s-%s-%s-%s-%s",
                sb.substring(0, 8),
                sb.substring(8, 12),
                sb.substring(12, 16),
                sb.substring(16, 20),
                sb.substring(20, 32));
        return x;
    }
}
