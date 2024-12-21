import com.sun.net.httpserver.Headers;

import java.io.IOException;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class TokenManager {
    private static final String DB_URL = "jdbc:sqlite:tokens.db";

    // 构造函数，初始化数据库连接并创建表
    public TokenManager() {
        // 创建表
        String createTableSQL = "CREATE TABLE IF NOT EXISTS tokens ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "long_term_token TEXT NOT NULL UNIQUE, "
                + "temp_token TEXT, "
                + "temp_token_expiry INTEGER"
                + ");";

        try (Connection conn = this.connect();
             Statement stmt = conn.createStatement()) {
            stmt.execute(createTableSQL);
//            System.out.println("Table 'tokens' is ready.");
        } catch (SQLException e) {
            System.out.println("Error creating table: " + e.getMessage());
        }
    }

    // 连接到SQLite数据库
    private Connection connect() {
        Connection conn = null;
        try {
            conn = DriverManager.getConnection(DB_URL);
            Statement stmt = conn.createStatement();
            stmt.execute("PRAGMA foreign_keys = ON;");
        } catch (SQLException e) {
            System.out.println("Connection error: " + e.getMessage());
        }
        return conn;
    }

    // 检查长期令牌是否存在
    public boolean isLongTermTokenExists(String longTermToken) {
        String query = "SELECT id FROM tokens WHERE long_term_token = ?";
        try (Connection conn = this.connect();
             PreparedStatement pstmt = conn.prepareStatement(query)) {
            pstmt.setString(1, longTermToken);
            ResultSet rs = pstmt.executeQuery();
            return rs.next();
        } catch (SQLException e) {
            System.out.println("Error checking long_term_token: " + e.getMessage());
            return false;
        }
    }

    // 添加新的长期令牌记录
    public boolean addLongTermToken(String longTermToken, String tempToken, long tempTokenExpiry) {
        String insertSQL = "INSERT INTO tokens(long_term_token, temp_token, temp_token_expiry) VALUES(?, ?, ?)";
        try (Connection conn = this.connect();
             PreparedStatement pstmt = conn.prepareStatement(insertSQL)) {
            pstmt.setString(1, longTermToken);
            pstmt.setString(2, tempToken);
            pstmt.setLong(3, tempTokenExpiry);
            pstmt.executeUpdate();
            System.out.println("Long-term token added successfully.");
            return true;
        } catch (SQLException e) {
            System.out.println("Error adding long-term token: " + e.getMessage());
            return false;
        }
    }

    public String getRandomLongTermToken() {
        String query = "SELECT long_term_token FROM tokens";
        try (Connection conn = this.connect();
             PreparedStatement pstmt = conn.prepareStatement(query)) {
            ResultSet rs = pstmt.executeQuery();

            // 将所有 long_term_token 添加到列表
            List<String> tokens = new ArrayList<>();
            while (rs.next()) {
                tokens.add(rs.getString("long_term_token"));
            }

            // 随机选择一个
            if (!tokens.isEmpty()) {
                Random random = new Random();
                int index = random.nextInt(tokens.size());
                return tokens.get(index);
            } else {
                System.out.println("No tokens found in the database.");
                return null;
            }
        } catch (SQLException e) {
            System.out.println("Error retrieving long_term_token: " + e.getMessage());
            return null;
        }
    }
    // 获取临时令牌
    public String getTempToken(String longTermToken) {
        String query = "SELECT temp_token FROM tokens WHERE long_term_token = ?";
        try (Connection conn = this.connect();
             PreparedStatement pstmt = conn.prepareStatement(query)) {
            pstmt.setString(1, longTermToken);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
//                GetToken.extractTimestamp(rs.getString("temp_token"));
                return rs.getString("temp_token");
            }
        } catch (SQLException e) {
            System.out.println("Error retrieving temp_token: " + e.getMessage());
        }
        return null;
    }

    // 获取临时令牌的过期时间
    public long getTempTokenExpiry(String longTermToken) {
        String query = "SELECT temp_token_expiry FROM tokens WHERE long_term_token = ?";
        try (Connection conn = this.connect();
             PreparedStatement pstmt = conn.prepareStatement(query)) {
            pstmt.setString(1, longTermToken);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getLong("temp_token_expiry");
            }
        } catch (SQLException e) {
            System.out.println("Error retrieving temp_token_expiry: " + e.getMessage());
        }
        return 0;
    }

    // 更新临时令牌及其过期时间
    public boolean updateTempToken(String longTermToken, String newTempToken, long newExpiry) {
        String updateSQL = "UPDATE tokens SET temp_token = ?, temp_token_expiry = ? WHERE long_term_token = ?";
        try (Connection conn = this.connect();
             PreparedStatement pstmt = conn.prepareStatement(updateSQL)) {
            pstmt.setString(1, newTempToken);
            pstmt.setLong(2, newExpiry);
            pstmt.setString(3, longTermToken);
            int affectedRows = pstmt.executeUpdate();
            if (affectedRows > 0) {
                System.out.println("Temp token updated successfully.");
                return true;
            }
        } catch (SQLException e) {
            System.out.println("Error updating temp_token: " + e.getMessage());
        }
        return false;
    }

}
