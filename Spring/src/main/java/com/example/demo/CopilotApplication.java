package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;

import java.net.ServerSocket;

@SpringBootApplication
public class CopilotApplication {

    public static void main(String[] args) {
        SpringApplication.run(CopilotApplication.class, args);
    }

    @Bean
    public WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> webServerFactoryCustomizer() {
        return factory -> {
            int port = getPort(8080); // 从8080开始尝试
            factory.setPort(port);
        };
    }

    private int getPort(int startPort) {
        int port = startPort;
        while (!isPortAvailable(port)) {
            port++;
            if (port > 65535) {
                throw new RuntimeException("No available port found");
            }
        }
        System.out.println("Server will start on port: " + port);
        return port;
    }

    private boolean isPortAvailable(int port) {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            serverSocket.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
