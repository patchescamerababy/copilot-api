package com.example.demo.util;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class TokenManager {
    private static final String DB_URL = "jdbc:sqlite:tokens.db";

    public TokenManager() {
        String createTableSQL = "CREATE TABLE IF NOT EXISTS tokens (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "username TEXT, " +
                "long_term_token TEXT NOT NULL UNIQUE, " +
                "temp_token TEXT, " +
                "temp_token_expiry INTEGER" +
                ");";
        try (Connection conn = this.connect();
             Statement stmt = conn.createStatement()) {
            stmt.execute(createTableSQL);
        } catch (SQLException e) {
            System.out.println("Error creating table: " + e.getMessage());
        }
    }

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

    public boolean addLongTermToken(String longTermToken, String tempToken, long tempTokenExpiry, String username) {
        String insertSQL = "INSERT INTO tokens(username, long_term_token, temp_token, temp_token_expiry) VALUES(?, ?, ?, ?)";
        try (Connection conn = this.connect();
             PreparedStatement pstmt = conn.prepareStatement(insertSQL)) {
            pstmt.setString(1, username);
            pstmt.setString(2, longTermToken);
            pstmt.setString(3, tempToken);
            pstmt.setLong(4, tempTokenExpiry);
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
            List<String> tokens = new ArrayList<>();
            while (rs.next()) {
                tokens.add(rs.getString("long_term_token"));
            }
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

    public String getTempToken(String longTermToken) {
        String query = "SELECT temp_token FROM tokens WHERE long_term_token = ?";
        try (Connection conn = this.connect();
             PreparedStatement pstmt = conn.prepareStatement(query)) {
            pstmt.setString(1, longTermToken);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getString("temp_token");
            }
        } catch (SQLException e) {
            System.out.println("Error retrieving temp_token: " + e.getMessage());
        }
        return null;
    }

    public String getUsername(String longTermToken) {
        String query = "SELECT username FROM tokens WHERE long_term_token = ?";
        try (Connection conn = this.connect();
             PreparedStatement pstmt = conn.prepareStatement(query)) {
            pstmt.setString(1, longTermToken);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getString("username");
            }
        } catch (SQLException e) {
            System.out.println("Error retrieving username: " + e.getMessage());
        }
        return null;
    }

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
