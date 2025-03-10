import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

public class Main {

    private static final Pattern NUMBER_PATTERN = Pattern.compile("-?\\d+(\\.\\d+)?");

    private static boolean isPort(String arg) {
        return arg != null && NUMBER_PATTERN.matcher(arg).matches();
    }

    /**
     * Creates and returns an HttpServer instance, attempting to bind to the specified port
     */
    public static HttpServer createHttpServer(int port) throws IOException {
        if(port<0 ||port > 65535){
            System.err.println("Invalid port number. Exiting.");
            System.exit(1);
        }
        HttpServer server = null;

        // Loop to try to find an available port
        while (server == null) {
            try {
                server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
                System.out.println("Server started on port " + port);
            } catch (BindException e) {
                if (port < 65535) {
                    System.out.println("Port " + port + " is already in use. Trying port " + (port + 1));
                    port++; // Increment the port number
                } else {
                    System.err.println("All ports are in use. Exiting.");
                    System.exit(1);
                }
            }
        }
        return server;
    }

    public static void main(String[] args) throws IOException {
        if(System.getProperty("os.name").toLowerCase().contains("windows")) {
            //cmd chcp 65001
            ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "chcp 65001");
            Process p = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), Charset.forName("GBK")))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                }
            }
        }
        try {
            OutputStream os = System.out;  // 获取 System.out 的 OutputStream
            PrintStream ps = new PrintStream(os, true, "GBK");
            System.setOut(ps);  // 设置新的 System.out 输出流
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        int port = 80;

        if (args.length > 0) {
            if (isPort(args[0])) {
                port = Integer.parseInt(args[0]);
            } else {
                System.out.println("Usage: java -jar <jar file> <port>");
                System.exit(1);
            }
        }
        ExecutorService executor = Executors.newFixedThreadPool(10);
        HttpServer server = createHttpServer(port);
        server.createContext("/v1/chat/completions", new CompletionHandler());
        server.createContext("/v1/embeddings", new EmbeddingHandler());
        server.createContext("/v1/models", new ModelsHandler());
        server.setExecutor(executor);
        server.start();
    }
}
