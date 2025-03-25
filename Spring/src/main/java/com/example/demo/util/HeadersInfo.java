package com.example.demo.util;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public class HeadersInfo {
    private static final String HEX_CHARS = "0123456789abcdef";
    private static final SecureRandom secureRandom = new SecureRandom();
    private static final String uuid = UUID.randomUUID().toString() + String.valueOf((long)(Math.random() * 1e13));
    private static final String vscode_machineid = generateRandomHex(64);
    public static final String openai_organization = "github-copilot";
    public static final String editor_version = "vscode/1.98.0-insider";
    public static final String editor_plugin_version = "copilot/1.270.0";
    public static final String copilot_language_server_version = "1.270.0";
    public static final String x_github_api_version = "2025-01-21";
    public static final String content_type = "application/json";
    public static final String user_agent = "GitHubCopilotChat/1.270.0";

    public static Map<String, String> getCopilotHeaders(){
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", content_type);
        headers.put("Connection", "keep-alive");
        headers.put("Editor-Plugin-Version", editor_plugin_version);
        headers.put("Editor-Version", editor_version);
        headers.put("Openai-Organization", openai_organization);
        headers.put("User-Agent", user_agent);
        headers.put("VScode-MachineId", vscode_machineid);
        headers.put("VScode-SessionId", uuid);
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
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        for (int i = 0; i < length; i++) {
            int index = random.nextInt(chars.length());
            sb.append(chars.charAt(index));
        }
        return String.format("%s-%s-%s-%s-%s",
                sb.substring(0, 8),
                sb.substring(8, 12),
                sb.substring(12, 16),
                sb.substring(16, 20),
                sb.substring(20, 32));
    }
}
