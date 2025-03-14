import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public class SystemMonitorServer {
    private static int PORT = 50000;
    private ServerSocket serverSocket;
    private ExecutorService threadPool;
    private Map<String, ClientHandler> userClients;
    private Map<String, ClientHandler> adminClients;
    private final Object lock = new Object();
    private ScheduledExecutorService scheduledExecutor;

    public SystemMonitorServer() {
        loadConfig();

        threadPool = Executors.newCachedThreadPool();
        userClients = new ConcurrentHashMap<>();
        adminClients = new ConcurrentHashMap<>();
    }

    public void start() {
        try {
            serverSocket = new ServerSocket(PORT);
            System.out.println("[*] Server Lắng Nghe Tại Port " + PORT);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("[*] Kết Nối Mới Từ: " + clientSocket.getInetAddress().getHostAddress());
                clientSocket.setSoTimeout(30000); //Đặt 30 giây timeout
                ClientHandler handler = new ClientHandler(clientSocket);
                threadPool.execute(handler);
            }
        } catch (IOException e) {
            System.err.println("Lỗi: " + e.getMessage());
            e.printStackTrace();
        } finally {
            shutdown(); //Thực hiện shutdown server nếu lỗi try catch xảy ra, gửi mess cho toàn user + admin
        }
    }

    private void loadConfig() {
        try {
            // Xác định đường dẫn thư mục chương trình
            String programDir = getProgramDirectory();
            File configFile = new File(programDir, "configSystemMonitorServer.ini");

            // Nếu file không tồn tại, tạo file với giá trị mặc định
            if (!configFile.exists()) {
                try (FileWriter writer = new FileWriter(configFile)) {
                    writer.write("PORT=50000");
                }
                System.out.println("[*] Đã tạo file cấu hình mặc định: " + configFile.getAbsolutePath());
                return; // Sử dụng giá trị mặc định
            }

            // Đọc file cấu hình
            Properties props = new Properties();
            props.load(new FileInputStream(configFile));

            // Lấy giá trị PORT
            String portStr = props.getProperty("PORT");
            if (portStr != null && !portStr.trim().isEmpty()) {
                try {
                    PORT = Integer.parseInt(portStr.trim());
                    System.out.println("[*] Đã đọc PORT=" + PORT + " từ file cấu hình: " + configFile.getAbsolutePath());
                } catch (NumberFormatException e) {
                    System.err.println("[!] Giá trị PORT không hợp lệ trong file cấu hình. Sử dụng giá trị mặc định: " + PORT);
                }
            }
        } catch (IOException e) {
            System.err.println("[!] Lỗi đọc file cấu hình: " + e.getMessage());
            System.out.println("[*] Sử dụng giá trị mặc định: PORT=" + PORT);
        }
    }

    // Phương thức xác định thư mục chứa chương trình
    private String getProgramDirectory() {
        try {
            // Lấy đường dẫn của class hiện tại
            String path = SystemMonitorServer.class.getProtectionDomain().getCodeSource().getLocation().getPath();
            // Giải mã URL
            path = java.net.URLDecoder.decode(path, "UTF-8");

            File jarFile = new File(path);
            if (jarFile.isFile()) {  // Nếu là file JAR
                return jarFile.getParentFile().getAbsolutePath();
            } else {  // Nếu là thư mục class
                return jarFile.getAbsolutePath();
            }
        } catch (Exception e) {
            System.err.println("[!] Không thể xác định thư mục chương trình: " + e);
            // Trả về đường dẫn hiện tại nếu có lỗi
            return System.getProperty("user.dir");
        }
    }

    private void shutdown() {
        try {
            JSONObject shutdownMsg = new JSONObject();
            shutdownMsg.put("messageType", "serverShutdown");
            for (ClientHandler handler : userClients.values()) {
                try {
                    handler.sendMessage(shutdownMsg.toString());
                } catch (Exception e) {
                }
            }

            // Notify admins
            for (ClientHandler handler : adminClients.values()) {
                try {
                    handler.sendMessage(shutdownMsg.toString());
                } catch (Exception e) {
                }
            }

            // Đóng kết nối
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }

            // Giải phóng các thread pool
            if (threadPool != null) {
                threadPool.shutdown();
                try {
                    // Give tasks 5 seconds to complete
                    if (!threadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                        threadPool.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    threadPool.shutdownNow();
                }
            }
        } catch (Exception e) {
            System.err.println("Lỗi khi cố gắng shutdown server: " + e.getMessage());
        }
    }

    private String createUserStatusMessage(String userId, boolean isConnected) {
        try {
            JSONObject message = new JSONObject();
            message.put("messageType", isConnected ? "userConnected" : "userDisconnected");
            message.put("userId", userId);
            return message.toString();
        } catch (JSONException e) {
            System.err.println("Lỗi khi cố tạo trạng thái cho user: " + e.getMessage());
            return "{}";
        }
    }

    private void broadcastToAdmins(String message) {
        for (ClientHandler admin : adminClients.values()) {
            try {
                admin.sendMessage(message);
            } catch (Exception e) {
                System.err.println("Lỗi khi gửi tin nhắn đến cho admin: " + e.getMessage());
            }
        }
    }

    private class ClientHandler implements Runnable {
        private Socket clientSocket;
        private BufferedReader in;
        private PrintWriter out;
        private String clientId = "unknown";
        private String clientType = "unknown"; // "admin" or "user"
        private boolean registered = false;

        public ClientHandler(Socket socket) {
            this.clientSocket = socket;
        }

        @Override
        public void run() {
            try {
                in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                out = new PrintWriter(clientSocket.getOutputStream(), true);

                //Tránh lỗi khi dùng máy khác ping máy chủ đang chạy
                String registrationMsg = in.readLine();
                if (registrationMsg == null) {
                    System.out.println("[*] Client disconnected before registration");
                    return;
                }

                JSONObject json = new JSONObject(registrationMsg);
                if (!json.has("type") || !json.has("clientId")) {
                    System.out.println("[*] Không tồn tại giá trị type hoặc clientId --> Có thể là attacker, chú ý");
                    return;
                }

                clientType = json.getString("type");
                clientId = json.getString("clientId");

                // Validate client type
                if (!"user".equals(clientType) && !"admin".equals(clientType)) {
                    System.out.println("[*] Lỗi giá trị type : " + clientType + " --> Có thể là attacker, chú ý");
                    return;
                }

                // Registration successful
                registered = true;

                // Remove socket timeout after successful registration
                clientSocket.setSoTimeout(0);

                // Register client in appropriate map
                if ("user".equals(clientType)) {
                    synchronized (lock) {
                        // Kiểm tra xem có ID Client tồn tại chưa, nếu có thì tiến hành thay thế nó bằng cái mới
                        ClientHandler existingHandler = userClients.put(clientId, this);
                        if (existingHandler != null && existingHandler != this) {
                            System.out.println("[*] Tiến hành thay thế clientId đã tồn tại: " + clientId);
                            try {
                                existingHandler.disconnect();
                            } catch (Exception e) {
                                // Ignore errors disconnecting old client
                            }
                        }
                        // Notify all admins about new user
                        broadcastToAdmins(createUserStatusMessage(clientId, true));
                    }
                } else if ("admin".equals(clientType)) {
                    synchronized (lock) {
                        adminClients.put(clientId, this);
                        sendConnectedUsersList();//Gửi danh sách user clientId đã kết nối đến server cho admin
                    }
                }

                System.out.println("[*] Client: " + clientType + " - " + clientId +
                        " - " + clientSocket.getInetAddress().getHostAddress());

                // Process messages
                String inputLine;
                while ((inputLine = in.readLine()) != null) {
                    processMessage(inputLine);
                }
            } catch (SocketTimeoutException e) {
                System.out.println("[*] Timeout: " + clientSocket.getInetAddress().getHostAddress() + " --> Có thể là attacker, chú ý");
            } catch (SocketException e) {
                if (registered) {
                    System.out.println("[*] Client " + clientType + " " + clientId + " mất kết nối: " + e.getMessage());
                } else {
                    System.out.println("[*] Client không đăng ký type, clientId mất kết nối " + " --> Có thể là attacker, chú ý");
                }
            } catch (IOException e) {
                System.err.println("Lỗi khi thực hiện kết nối đến clientId " + (registered ? clientId : "unregistered") + ": " + e.getMessage());
            } catch (JSONException e) {
                System.err.println("JSON Lỗi: " + e.getMessage());
            } finally {
                disconnect();
            }
        }

        private void processMessage(String message) {
            try {
                JSONObject json = new JSONObject(message);
                String messageType = json.getString("messageType");

                if ("admin".equals(clientType)) {
                    // Process admin message
                    switch (messageType) {
                        case "requestSystemInfo":
                        case "executeCommand":
                        case "requestScreenshot":
                        case "requestProcessList":
                        case "killProcess":
                        case "shutdownUser":
                        case "logoutUser":
                        case "startKeyLogger":
                        case "stopKeyLogger":
                        case "startScreenStream":
                        case "stopScreenStream":
                            forwardToUser(json.getString("targetUserId"), message);
                            break;
                    }
                } else if ("user".equals(clientType)) {
                    // Process user message
                    switch (messageType) {
                        case "systemInfoResponse":
                        case "screenshotResponse":
                        case "processListResponse":
                        case "commandResponse":
                        case "keyLogData":
                        case "screenStreamFrame":
                            forwardToAdmin(json.getString("targetAdminId"), message);
                            break;
                    }
                }
            } catch (JSONException e) {
                System.err.println("Error processing message: " + e.getMessage());
            }
        }

        private void forwardToUser(String userId, String message) {
            ClientHandler userHandler = userClients.get(userId);
            if (userHandler != null) {
                try {
                    userHandler.sendMessage(message);
                } catch (Exception e) {
                    System.err.println("Error forwarding message to user " + userId + ": " + e.getMessage());
                    try {
                        JSONObject response = new JSONObject();
                        response.put("messageType", "error");
                        response.put("error", "Error communicating with user " + userId);
                        sendMessage(response.toString());
                    } catch (JSONException je) {
                        System.err.println("Error creating error message: " + je.getMessage());
                    }
                }
            } else {
                try {
                    // Inform admin that user is not connected
                    JSONObject response = new JSONObject();
                    response.put("messageType", "error");
                    response.put("error", "User " + userId + " is not connected");
                    sendMessage(response.toString());
                } catch (JSONException e) {
                    System.err.println("Error creating error message: " + e.getMessage());
                }
            }
        }

        private void forwardToAdmin(String adminId, String message) {
            ClientHandler adminHandler = adminClients.get(adminId);
            if (adminHandler != null) {
                try {
                    adminHandler.sendMessage(message);
                } catch (Exception e) {
                    System.err.println("Lỗi gửi tin nhắn đến: " + adminId + ": " + e.getMessage());
                }
            } else {
               System.out.println("[*] Không thể gửi tin nhắn đến Client Admin " + adminId + " không tồn tại/ngắt kết nối");
               adminClients.remove(clientId);
            }
        }

        private void sendConnectedUsersList() {
            try {
                JSONObject message = new JSONObject();
                message.put("messageType", "connectedUsers");

                JSONArray usersArray = new JSONArray();
                for (String userId : userClients.keySet()) {
                    usersArray.put(userId);
                }

                message.put("users", usersArray);
                sendMessage(message.toString());
            } catch (JSONException e) {
                System.err.println("Lỗi khi tạo danh sách users cho admin: " + e.getMessage());
            }
        }

        public void sendMessage(String message) {
            out.println(message);
            if (out.checkError()) {
                throw new RuntimeException("Lỗi khi flush messages");
            }
        }

        public void disconnect() {
            try {
                if (registered) {
                    if ("user".equals(clientType)) {
                        synchronized (lock) {
                            // Only remove if it's the same handler
                            if (userClients.get(clientId) == this) {
                                userClients.remove(clientId);
                                broadcastToAdmins(createUserStatusMessage(clientId, false)); //Gửi mess đến toàn bộ admin
                                System.out.println("[*] Client User: " + clientId + " Mất Kết Nối");
                            }
                        }
                    } else if ("admin".equals(clientType)) {
                        synchronized (lock) {
                            if (adminClients.get(clientId) == this) {
                                adminClients.remove(clientId);
                                System.out.println("[*] Client Admin: " + clientId + " Mất Kết Nối");
                            }
                        }
                    }
                } else {
                    System.out.println("[*] Client không đăng ký type, clientId mất kết nối : " + " --> Có thể là attacker, chú ý" + (clientSocket != null ? clientSocket.getInetAddress().getHostAddress() : "unknown"));
                }

                if (in != null) in.close();
                if (out != null) out.close();
                if (clientSocket != null && !clientSocket.isClosed()) clientSocket.close();
            } catch (IOException e) {
                System.err.println("[*] Có lỗi xảy ra khi cố gắng ngắt kết nối ???: " + e.getMessage());
            }
        }
    }

    public static void main(String[] args) {
        SystemMonitorServer server = new SystemMonitorServer();
        server.start();
    }
}