import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

public class Main {
    private static final Pattern NUMBER_PATTERN = Pattern.compile("-?\\d+(\\.\\d+)?");
    private static boolean isPort(String arg) {return arg != null && NUMBER_PATTERN.matcher(arg).matches();}

    /**
     * 创建并返回一个HttpServer实例，尝试绑定到指定端口
     */
    public static HttpServer createHttpServer(int initialPort) throws IOException {
        int port = initialPort;
        HttpServer server = null;

        // 循环尝试找到一个可用的端口
        while (server == null) {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
                System.out.println("Server started on port " + port);
            } catch (BindException e) {
                if (port < 65535) {
                    System.out.println("Port " + port + " is already in use. Trying port " + (port + 1));
                    port++; // 端口号加1
                } else {
                    System.err.println("All ports from " + initialPort + " to 65535 are in use. Exiting.");
                    System.exit(1);
                }
            }
        }
        return server;
    }

    public static void main(String[] args) throws IOException {
        int port = 80;

        if (args.length > 0) {
            if (isPort(args[0])) {
                port = Integer.parseInt(args[0]);
            } else {
                System.out.println("Usage: java -jar <jarfile> <port>");
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
