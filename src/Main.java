import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

public class Main {

    public static int port = 80;
    private static final Pattern NUMBER_PATTERN = Pattern.compile("-?\\d+(\\.\\d+)?");

    private static boolean isPort(String arg) {
        return arg != null && NUMBER_PATTERN.matcher(arg).matches();
    }
    static {
        // 强制 pure-java 模式必须最早设置
        System.setProperty("sqlite.purejava", "true");

        // 处理临时目录：如果默认 temp 包含非 ASCII（比如中文），换成 ASCII-safe 目录
        String defaultTmp = System.getProperty("java.io.tmpdir");
        System.setProperty("file.encoding", "UTF-8");




        try {

            if (containsNonAscii(defaultTmp)) {
                if (System.getProperty("os.name").toLowerCase().contains("windows")) {

                    // 基于 C:\temp 创建一个唯一子目录
                    Path base = Paths.get("C:\\temp");
                    if (!Files.exists(base)) {
                        Files.createDirectories(base);
                    }
                    Path safeTmp = Files.createTempDirectory(base, "apptmp-");
                    System.setProperty("java.io.tmpdir", safeTmp.toAbsolutePath().toString());

                    //执行cmd chcp 65001
                    ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "chcp 65001");
                    Process p = pb.start();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), Charset.forName("GBK")));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.out.println(line);
                    }
                    OutputStream os = System.out;  // 获取 System.out 的 OutputStream
                    PrintStream ps = new PrintStream(os, true, "GBK");
                    System.setOut(ps);  // 设置新的 System.out 输出流


                }else{
                    Path base = Paths.get("/tmp");
                    if (!Files.exists(base)) {
                        Files.createDirectories(base);
                    }
                    Path safeTmp = Files.createTempDirectory(base, "apptmp-");
                    System.setProperty("java.io.tmpdir", safeTmp.toAbsolutePath().toString());
                }

            }else{
                File tempDir = new File("temp");
                if (!tempDir.exists()) {
                    if (!tempDir.mkdir()) {
                        System.err.println("Failed to create temp directory.");
                        System.exit(1);
                    }
                }
                //get temp directory absolute path
                String tempDirPath = tempDir.getAbsolutePath();
                System.setProperty("java.io.tmpdir", tempDirPath);
            }


        } catch (Exception e) {
            throw new RuntimeException(e);
        }


    }

    private static boolean containsNonAscii(String s) {
        for (char c : s.toCharArray()) {
            if (c > 127) return true;
        }
        return false;
    }
    /**
     * Displays help information for the application
     */
    private static void printHelp() {
        System.out.println("Usage: java -jar Copilot.jar [options]");
        System.out.println("Options:");
        System.out.println("  -h, --help                 Display this help message");
        System.out.println("  -p, --port <number>        Specify the port number (default: 80)");
//        System.exit(0);
    }

    /**
     * Parse command line arguments
     * @param args Command line arguments
     * @return The port number specified or the default port
     */
    private static int parseArgs(String[] args) {
        int p = port;
        if(args.length==0) {
            printHelp();
        }
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];

            switch (arg) {
                case "-h":
                case "--help":
                    printHelp();
                    System.exit(0);
//                    break;
                case "-p":
                case "--port":
                    if (i + 1 < args.length) {
                        try {
                            p = Integer.parseInt(args[++i]);
                        } catch (NumberFormatException e) {
                            System.err.println("Error: Port must be a number");
                            printHelp();
//                            System.exit(0);
                        }
                    } else {
                        System.err.println("Error: Port number is missing");
                        printHelp();
//                        System.exit(0);
                    }
                    break;
                default:
                    System.err.println("Unknown option: " + arg);
                    printHelp();
//                    System.exit(0);
            }
        }

        return p;
    }

    /**
     * Creates and returns an HttpServer instance, attempting to bind to the specified port
     */
    public static HttpServer createHttpServer(int port) {
        if (port < 0 || port > 65535) {
            System.err.println("Invalid port number. Exiting.");
            System.exit(1);
        }
        HttpServer server = null;

        // Loop to try to find an available port
        while (server == null) {
            try {
                server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
                System.out.println("Server started on port " + port);
            } catch (IOException e) {
                if (port < 65535) {
                    System.err.println("Port " + port + " is already in use. Trying port " + (port + 1));
                    port++; // Increment the port number
                } else {
                    System.err.println("All ports are in use. Exiting.");
                    System.exit(1);
                }
            }
        }
        return server;
    }
    public static void main(String[] args) {

        int p = parseArgs(args);

        ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        HttpServer server = createHttpServer(p);
//        server.createContext("/v1/chat/completions", new CompletionHandler());
//        server.createContext("/v1/embeddings", new EmbeddingHandler());
//        server.createContext("/v1/models", new ModelsHandler());
        server.createContext("/v1/chat/completions", exchange -> new CompletionHandler().handle(exchange));
        server.createContext("/v1/embeddings", exchange -> new EmbeddingHandler().handle(exchange));
        server.createContext("/v1/models", exchange -> new ModelsHandler().handle(exchange));
        server.setExecutor(executor);
        server.start();
    }

}
