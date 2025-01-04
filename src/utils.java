import com.sun.net.httpserver.HttpExchange;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.locks.ReentrantLock;

public class utils {
    private static final ReentrantLock tokenLock = new ReentrantLock();
    private static final TokenManager tokenManager = new TokenManager();
    private static final Logger logger = LogManager.getLogger(utils.class);

    public static String GetToken(String longTermToken) {
        try {
            URL url = new URL("https://api.github.com/copilot_internal/v2/token");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(60000); // 60 seconds
            connection.setReadTimeout(60000); // 60 seconds
            connection.setRequestProperty("Authorization", "token " + longTermToken);
            connection.setRequestProperty("Editor-Plugin-Version", HeadersInfo.editor_plugin_version);
            connection.setRequestProperty("Editor-Version", HeadersInfo.editor_version);
            connection.setRequestProperty("User-Agent", HeadersInfo.user_agent);
            connection.setRequestProperty("x-github-api-version", HeadersInfo.x_github_api_version);
            connection.setRequestProperty("Sec-Fetch-Site", "none");
            connection.setRequestProperty("Sec-Fetch-Mode", "no-cors");
            connection.setRequestProperty("Sec-Fetch-Dest", "empty");

            int responseCode = connection.getResponseCode();

            if (responseCode >= 200 && responseCode < 300) {
                String responseBody = readStream(connection.getInputStream());
                JSONObject jsonObject = new JSONObject(responseBody);
                System.out.println(responseBody);
                if (jsonObject.has("token")) {
                    String token = jsonObject.getString("token");
                    System.out.println("\nNew Token:\n " + token);
                    return token;
                } else {
                    System.out.println("\"token\" field not found in the response.");
                }
            } else {
                String errorResponse = readStream(connection.getErrorStream());
                System.out.println("Request failed, status code: " + responseCode);
                System.out.println("Response body: " + errorResponse);
            }
            return null;
        } catch (Exception e) {
            logger.error("Failed to get token: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Get a valid short-term token. If it does not exist or has expired, generate a new short-term token and update the database.
     *
     * @param longTermToken Long-term token
     * @return Valid short-term token
     * @throws IOException If an error occurs while fetching or updating the token
     */
    public static String getValidTempToken(String longTermToken) throws IOException {
        // If the token does not exist or has expired, acquire the lock to prevent multi-threaded competition
        tokenLock.lock();
        try {
            // Recheck to prevent competition in a multi-threaded environment
            String tempToken = tokenManager.getTempToken(longTermToken);

            if (isTokenExpired(tempToken)) {
                System.out.println("Token has expired");
                // Generate a new short-term token
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

    /**
     * Send error response
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
            logger.error("Failed to send error response: {}", e.getMessage());
        }
    }

    public static int extractTimestamp(String input) {
        // Use split method to split the string with ";"
        String[] splitArray = input.split(";");

        // Traverse the split array
        for (String part : splitArray) {
            // If the part containing "exp=" is found
            if (part.startsWith("exp=")) {
                return Integer.parseInt(part.substring(4));
            }
        }
        // If not found, return a default value, such as 0
        return 0;
    }

    /**
     * Check if the token has expired
     *
     * @param token Token string, format like "tid=b91081296b85fc09f76d3c4ac8f0a6a6;exp=1731950502"
     * @return Returns true if expired, false if not expired
     */
    public static boolean isTokenExpired(String token) {
        int exp = extractTimestamp(token);

        int currentEpoch = (int) Instant.now().getEpochSecond();
        // Format timestamp as "yyyy/MM/dd HH:mm:ss"
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");

        // Format expiration time
        LocalDateTime expirationTime = LocalDateTime.ofInstant(Instant.ofEpochSecond(exp), ZoneId.systemDefault());
        String formattedExpiration = expirationTime.format(formatter);

        // Format current time
        LocalDateTime currentTime = LocalDateTime.ofInstant(Instant.ofEpochSecond(currentEpoch), ZoneId.systemDefault());
        String formattedCurrent = currentTime.format(formatter);

        // Calculate remaining time (minutes and seconds)
        int remainingSeconds = exp - currentEpoch;
        int minutes = remainingSeconds / 60;
        int seconds = remainingSeconds % 60;

        // Print results
        System.out.println("Current epoch: " + currentEpoch);
        System.out.println("Expiration epoch: " + exp);
        System.out.println("  Current  time: " + formattedCurrent);
        System.out.println("Expiration time: " + formattedExpiration);
        System.out.println("Remaining: " + minutes + " minutes " + seconds + " seconds");
        return exp < currentEpoch;
    }

    public static String getToken(String authorizationHeader, HttpExchange exchange) {

        String longTermToken;
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {

            // Use random long-term token
            longTermToken = tokenManager.getRandomLongTermToken();
            System.out.println("Using random long-term token: " + longTermToken);
        } else {
            // Extract long-term token
            longTermToken = authorizationHeader.substring("Bearer ".length()).trim();
            if (longTermToken.isEmpty()) {
                sendError(exchange, "Token is empty.", 401);
                return null;
            }

            // Check if the token prefix is "ghu" or "gho"
            if (!(longTermToken.startsWith("ghu") || longTermToken.startsWith("gho"))) {
                utils.sendError(exchange, "Invalid token prefix.", 401);
                return null;
            }

            // Check if the long-term token exists in the database
            if (!tokenManager.isLongTermTokenExists(longTermToken)) {
                // If not, add it
                String newTempToken = utils.GetToken(longTermToken); // Generate new temporary token
                if (newTempToken == null || newTempToken.isEmpty()) {
                    sendError(exchange, "Unable to generate a new temporary token.", 500);
                    return null;
                }
                int tempTokenExpiry = utils.extractTimestamp(newTempToken); // Assume the temporary token's expiration time can be obtained through this method
                boolean added = tokenManager.addLongTermToken(longTermToken, newTempToken, tempTokenExpiry);
                if (!added) {
                    sendError(exchange, "Unable to add long-term token.", 500);
                    return null;
                }
            }
        }

        // Get valid short-term token
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
     * Read input stream content as a string
     */
    private static String readStream(InputStream is) throws IOException {
        if (is == null) return "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ( (line = reader.readLine()) != null ) {
            sb.append(line).append("\n");
        }
        reader.close();
        return sb.toString();
    }
}
