package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;

import java.net.ServerSocket;

@SpringBootApplication
public class CopilotApplication {
private static int  port;
    public static void main(String[] args) {
        if(args.length>0){
            setPort(Integer.parseInt(args[0]));
        }else{
            setPort(8080);
        }

        SpringApplication.run(CopilotApplication.class, args);
    }

    @Bean
    public WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> webServerFactoryCustomizer() {

        return factory -> {
            int finalPort = getPort(port);
            factory.setPort(finalPort);
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
    private static void setPort(int port) {
        CopilotApplication.port = port;
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
