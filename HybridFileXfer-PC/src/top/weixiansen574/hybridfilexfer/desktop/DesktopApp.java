package top.weixiansen574.hybridfilexfer.desktop;

import top.weixiansen574.hybridfilexfer.core.TransferOptions;
import top.weixiansen574.hybridfilexfer.core.TransferProgress;
import top.weixiansen574.hybridfilexfer.core.TransferSummary;
import top.weixiansen574.hybridfilexfer.core.VerificationProgress;
import top.weixiansen574.hybridfilexfer.core.bean.TrafficInfo;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.prefs.Preferences;

import static top.weixiansen574.hybridfilexfer.desktop.DesktopText.*;
import static top.weixiansen574.hybridfilexfer.desktop.DesktopTheme.*;

/** Desktop connection and transfer console; all widgets are confined to the EDT. */
public final class DesktopApp {
    private final JFrame frame = new JFrame(text("多轨快传", "HybridFileXfer"));
    private final JComboBox<String> mode = new JComboBox<>(new String[]{text("USB / ADB", "USB / ADB"), text("Wi-Fi / 网络", "Wi-Fi / Network")});
    private final JComboBox<Object> devices = new JComboBox<>();
    private final JTextField address = new JTextField(18), directory = new JTextField(28);
    private final PortField port = new PortField(5740, 1, 65535);
    private final PortField localPort = new PortField(0, 0, 65535);
    private PortField advancedPort;
    private final JPasswordField pairing = new JPasswordField(18);
    private final JCheckBox reconnect = new JCheckBox(text("等待手机并自动重新连接", "Wait for phone and reconnect automatically"), true);
    private final JCheckBox launchPhone = new JCheckBox(text("通过 ADB 打开手机 App（手机上确认）", "Open phone app through ADB (confirm on phone)"), true);
    private final JCheckBox burst = new JCheckBox(text("实验性 ADB burst mode", "Experimental ADB burst mode"));
    private final JCheckBox verify = new JCheckBox(text("传输后校验 SHA-256（可选）", "SHA-256 after transfer (optional)"), false);
    private final JCheckBox resume = new JCheckBox(text("恢复匹配的未完成文件", "Resume matching partial files"), true);
    private final JCheckBox incremental = new JCheckBox(text("跳过已相同的文件", "Skip identical files"));
    private final JCheckBox prescan = new JCheckBox(text("传输前统计总量（大目录需要时间）", "Count totals before transfer (large folders take time)"));
    private final JCheckBox rememberHistory = new JCheckBox(text("保存最近 100 条摘要到本机", "Keep the latest 100 summaries on this computer"), true);
    private final JComboBox<String> conflict = new JComboBox<>(new String[]{text("保留两份", "Keep both"), text("跳过", "Skip"), text("覆盖", "Overwrite")});
    private final JButton connect = new JButton(text("等待并连接", "Wait & connect")), stop = new JButton(text("停止 / 断开", "Stop / disconnect"));
    private final JButton refresh = new JButton(text("刷新", "Refresh")), browse = new JButton(text("选择文件夹", "Choose folder"));
    private final JButton openPhone = new JButton(text("打开手机 App", "Open phone app"));
    private final JButton cancelTransfer = new JButton(text("取消本次传输", "Cancel transfer"));
    private final JTextArea status = new JTextArea(2, 50);
    private final JLabel phase = new JLabel(" "), file = new JLabel(" ");
    private final Pill connectionBadge = new Pill(), transferBadge = new Pill();
    private final JLabel progressCaption = new JLabel(), progressPercent = new JLabel();
    private final JLabel endpointLabel = new JLabel();
    private final JPanel endpointCards = transparent(new CardLayout());
    private JScrollPane contentScroll;
    private JPanel advancedPanel;
    private final JComboBox<ThemePreference> themeSelector = new JComboBox<>(ThemePreference.values());
    private final SystemThemeMonitor themeMonitor = new SystemThemeMonitor(this::refreshTheme);
    private final JProgressBar progress = new JProgressBar(0, 1000);
    private final JTextArea logs = new JTextArea();
    private final DefaultTableModel channels = readOnlyModel(text("通道", "Channel"), text("发送", "Upload"), text("接收", "Download"));
    private final DefaultTableModel history = readOnlyModel(text("时间", "Time"), text("方向", "Direction"), text("结果", "Result"), text("数据量", "Data"), text("耗时", "Duration"));
    private final ExecutorService utility = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "hfx-desktop-utility"); t.setDaemon(true); return t; });
    private final TransferHistory historyStore = new TransferHistory();
    private final List<String[]> historyRows = new ArrayList<>();
    private final List<JComponent> connectionControls = new ArrayList<>();
    private final ConnectionController controller;
    private final AtomicReference<TransferProgress> latestProgress = new AtomicReference<>();
    private final AtomicReference<VerificationProgress> latestVerification = new AtomicReference<>();
    private final ConcurrentLinkedQueue<String> pendingLogs = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingLogCount = new AtomicInteger(), omittedLogs = new AtomicInteger();
    private final Timer progressTimer = new Timer(100, event -> { flushProgress(); flushLogs(); });
    private Preferences prefs;
    private boolean uploading, taskFailed, transferActive, disposalStarted;
    private boolean verificationActive, verificationStopRequested, taskFinished;
    private volatile boolean closing;
    private long taskStarted, lastTransferredBytes;
    private String taskResult = "", sessionSecret = "", automaticSecret = "";
    private String transferResult = "", verificationResult = "";
    private volatile AdbBridge auxiliaryAdb;
    private int refreshGeneration;
    private int phoneRequestGeneration;
    private boolean phoneRequestInFlight;
    private AdbPairingSession pendingPhonePairing;
    private String pendingPhoneDevice;

    public static void launch(LaunchOptions options) {
        if (GraphicsEnvironment.isHeadless()) throw new IllegalStateException("No desktop display is available. Use --cli -c adb or --help.");
        SwingUtilities.invokeLater(() -> {
            try {
                configureLookAndFeel();
                DesktopApp app = new DesktopApp(options);
                app.frame.setVisible(true);
                app.refreshDevices();
            } catch (Exception e) {
                JOptionPane.showMessageDialog(null, ConnectionController.friendlyError(e), "HybridFileXfer", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    public DesktopApp(LaunchOptions options) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Create the desktop on the Swing event thread.");
        controller = new ConnectionController(new ConnectionController.Listener() {
            @Override public void onState(ConnectionController.State state, String message) { ui(() -> updateState(state, message)); }
            @Override public void onLog(String message) {
                if (pendingLogCount.incrementAndGet() <= 1000) pendingLogs.add(message);
                else { pendingLogCount.decrementAndGet(); omittedLogs.incrementAndGet(); }
            }
            @Override public void onPairing(String secret, int serverPort) {
                ui(() -> displayPairing(secret, serverPort));
            }
            @Override public void onChannels(List<String> names) { ui(() -> { channels.setRowCount(0); for (String name : names) channels.addRow(new Object[]{name, "0 B/s", "0 B/s"}); }); }
            @Override public void onSpeed(List<TrafficInfo> values) {
                List<TrafficInfo> snapshot = new ArrayList<>();
                for (TrafficInfo info : values) snapshot.add(new TrafficInfo(info.iName, info.uploadTraffic, info.downloadTraffic));
                ui(() -> updateSpeeds(snapshot));
            }
            @Override public void onTransferStarted(boolean upload) {
                latestProgress.set(null); latestVerification.set(null);
                ui(() -> started(upload));
            }
            @Override public void onProgress(TransferProgress value) {
                TransferProgress copy = new TransferProgress();
                copy.transferredBytes = value.transferredBytes; copy.totalBytes = value.totalBytes;
                copy.completedFiles = value.completedFiles; copy.totalFiles = value.totalFiles;
                copy.currentFileBytes = value.currentFileBytes; copy.currentFileSize = value.currentFileSize; copy.currentPath = value.currentPath;
                latestProgress.set(copy);
            }
            @Override public void onSummary(TransferSummary summary) { ui(() -> { flushProgress(); showSummary(summary); }); }
            @Override public void onVerificationProgress(VerificationProgress value) { latestVerification.set(value); }
            @Override public void onVerificationStopped(boolean upload, long bytes, long millis) {
                ui(() -> { flushProgress(); verificationStopped(upload, bytes, millis); });
            }
            @Override public void onComplete(boolean upload, long bytes, long millis) { ui(() -> { flushProgress(); completed(upload, bytes, millis); }); }
            @Override public void onTransferFailed(String message) { ui(() -> { flushProgress(); failed(message); }); }
            @Override public void onFinished() { ui(() -> { setBusy(false); if (closing) dispose(); }); }
        });
        try { if (!Boolean.getBoolean("hfx.smokeTest")) prefs = Preferences.userNodeForPackage(DesktopApp.class); } catch (SecurityException ignored) { }
        String downloads = Paths.get(System.getProperty("user.home"), "Downloads").toString();
        if (!Files.isDirectory(Paths.get(downloads))) downloads = System.getProperty("user.home");
        directory.setText("/".equals(options.directory) ? preference("directory", downloads) : options.directory);
        port.setPreferredSize(new Dimension(100, 28));
        localPort.setPreferredSize(new Dimension(100, 28));
        directory.setPreferredSize(new Dimension(390, 28));
        address.setPreferredSize(new Dimension(240, 28));
        pairing.setPreferredSize(new Dimension(240, 28));
        address.setText("adb".equals(options.connect) ? preference("address", "") : options.connect);
        mode.setSelectedIndex("adb".equals(options.connect) ? 0 : 1);
        port.setValue(options.port);
        localPort.setValue(options.localPort);
        pairing.setText(options.secret);
        burst.setSelected(options.burst);
        reconnect.setSelected(true);
        devices.addItem(text("自动选择唯一已授权手机", "Automatically select the only authorized phone"));
        if (options.device != null) devices.addItem(new AdbBridge.Device(options.device, "device", options.device));
        if (options.device != null) devices.setSelectedIndex(1);
        build();
        progressTimer.start();
        if (!Boolean.getBoolean("hfx.smokeTest")) themeMonitor.start();
        modeChanged();
        updateState(ConnectionController.State.IDLE, text("电脑已就绪。连接手机后，点击“开始连接”。", "Ready. Connect your phone, then choose Connect."));
        stop.setEnabled(false);
        cancelTransfer.setEnabled(false);
        utility.submit(() -> {
            try {
                List<String[]> rows = historyStore.load();
                ui(() -> { historyRows.addAll(rows); for (String[] row : rows) history.addRow(row); });
            } catch (Exception e) { ui(() -> appendLog(text("无法读取本地历史：", "Cannot read local history: ") + e.getMessage())); }
        });
    }
    private static DefaultTableModel readOnlyModel(String... columns) {
        return new DefaultTableModel(columns, 0) { @Override public boolean isCellEditable(int row, int column) { return false; } };
    }
    public static void configureLookAndFeel() throws Exception {
        ThemePreference appearance = ThemePreference.SYSTEM;
        try {
            if (!Boolean.getBoolean("hfx.smokeTest"))
                appearance = ThemePreference.fromString(Preferences.userNodeForPackage(DesktopApp.class).get("theme", "SYSTEM"));
        } catch (SecurityException ignored) { }
        DesktopTheme.configure(appearance);
    }

    private void changeTheme() {
        ThemePreference selected = (ThemePreference) themeSelector.getSelectedItem();
        if (selected == null) return;
        DesktopTheme.configure(selected);
        refreshTheme();
        try { if (prefs != null) prefs.put("theme", selected.name()); }
        catch (SecurityException ignored) { }
    }

    private void refreshTheme() {
        if (closing) return;
        refreshWindowTheme(frame);
        // The settings panel is kept between openings and may currently be detached.
        if (advancedPanel != null && SwingUtilities.getWindowAncestor(advancedPanel) == null) {
            SwingUtilities.updateComponentTreeUI(advancedPanel);
            refreshColors(advancedPanel);
        }
    }

    private static void refreshWindowTheme(Window window) {
        SwingUtilities.updateComponentTreeUI(window);
        refreshColors(window);
        window.invalidate(); window.validate(); window.repaint();
        for (Window child : window.getOwnedWindows()) if (child.isDisplayable()) refreshWindowTheme(child);
    }

    private String preference(String key, String fallback) {
        try { return prefs == null ? fallback : prefs.get(key, fallback); } catch (SecurityException e) { return fallback; }
    }
    private void build() {
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.setIconImages(Arrays.asList(applicationIcon(16), applicationIcon(32), applicationIcon(48),
                applicationIcon(128), applicationIcon(256)));
        Rectangle screen = frame.getGraphicsConfiguration().getBounds();
        Insets taskbar = Toolkit.getDefaultToolkit().getScreenInsets(frame.getGraphicsConfiguration());
        int availableWidth = screen.width - taskbar.left - taskbar.right;
        int availableHeight = screen.height - taskbar.top - taskbar.bottom;
        frame.setMinimumSize(new Dimension(Math.min(960, availableWidth), Math.min(680, availableHeight)));
        frame.setSize(Math.min(1120, availableWidth - 16), Math.min(900, availableHeight - 16));
        frame.setLocationRelativeTo(null);
        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBorder(new EmptyBorder(16, 24, 12, 24));
        bindBackground(root, () -> BACKGROUND);

        JPanel header = transparent(new BorderLayout(16, 0));
        JPanel brand = transparent(new BorderLayout(14, 0));
        brand.add(new JLabel(new BrandIcon()), BorderLayout.WEST);
        JPanel heading = transparent(new GridLayout(2, 1, 0, 2));
        heading.add(label(text("多轨快传", "HybridFileXfer"), 24f, true, TEXT));
        heading.add(label(text("手机与电脑，连接之后即刻传输", "Your phone and computer, ready to transfer"), 13f, false, MUTED));
        brand.add(heading);
        header.add(brand);
        JPanel badgeWrap = transparent(new FlowLayout(FlowLayout.RIGHT, 12, 10));
        themeSelector.setSelectedItem(DesktopTheme.getPreference());
        themeSelector.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                    boolean selected, boolean focus) {
                String name = value == ThemePreference.DARK ? text("深色", "Dark")
                        : value == ThemePreference.LIGHT ? text("浅色", "Light") : text("跟随系统", "System");
                return super.getListCellRendererComponent(list, name, index, selected, focus);
            }
        });
        themeSelector.setToolTipText(text("外观主题（默认跟随系统）", "Appearance (follows the system by default)"));
        themeSelector.getAccessibleContext().setAccessibleName(text("外观主题", "Appearance"));
        themeSelector.addActionListener(event -> changeTheme());
        badgeWrap.add(themeSelector);
        badgeWrap.add(connectionBadge);
        header.add(badgeWrap, BorderLayout.EAST);
        root.add(header, BorderLayout.NORTH);

        JPanel cards = new CardColumn();
        Card connection = new Card(new BorderLayout(0, 8));
        JPanel connectionHeading = transparent(new BorderLayout(12, 0));
        connectionHeading.add(label(text("连接设备", "Connect your device"), 16f, true, TEXT), BorderLayout.WEST);
        connectionHeading.add(label(text("USB 与 Wi-Fi，多通道协同传输", "USB and Wi-Fi, working together"), 12f, false, MUTED), BorderLayout.EAST);
        connection.add(connectionHeading, BorderLayout.NORTH);
        JPanel form = transparent(new GridBagLayout());
        mode.setPreferredSize(new Dimension(172, 38));
        mode.setMinimumSize(new Dimension(172, 38));
        devices.setPreferredSize(new Dimension(380, 38));
        devices.setMinimumSize(new Dimension(100, 38));
        address.setPreferredSize(new Dimension(320, 38));
        directory.setPreferredSize(new Dimension(290, 38));
        pairing.setPreferredSize(new Dimension(200, 38));
        port.setPreferredSize(new Dimension(112, 38));
        localPort.setPreferredSize(new Dimension(112, 38));
        directory.putClientProperty("JTextField.placeholderText", text("选择电脑上的文件夹", "Choose a computer folder"));
        address.putClientProperty("JTextField.placeholderText", "192.168.1.100");
        pairing.putClientProperty("JTextField.placeholderText", text("ADB 自动配对，或输入手机上的配对码", "ADB pairing, or the code shown on your phone"));
        pairing.putClientProperty("JTextField.showClearButton", true);
        refresh.setText(text("刷新设备", "Refresh"));
        browse.setText(text("浏览", "Browse"));
        openPhone.setText(text("打开手机 App", "Open phone app"));
        connect.setText(text("开始连接", "Connect"));
        stop.setText(text("断开连接", "Disconnect"));
        reconnect.setText(text("自动等待与重连", "Wait and reconnect automatically"));
        launchPhone.setText(text("连接时打开手机 App", "Open phone app on connect"));
        reconnect.setOpaque(false); launchPhone.setOpaque(false); rememberHistory.setOpaque(false);
        JPanel adbEndpoint = transparent(new BorderLayout(8, 0));
        adbEndpoint.add(devices); adbEndpoint.add(refresh, BorderLayout.EAST);
        JPanel networkEndpoint = transparent(new BorderLayout(8, 0));
        networkEndpoint.add(address);
        JPanel portField = transparent(new BorderLayout(8, 0));
        portField.add(label(text("端口", "Port"), 12f, false, MUTED), BorderLayout.WEST);
        portField.add(port);
        networkEndpoint.add(portField, BorderLayout.EAST);
        endpointCards.add(adbEndpoint, "adb"); endpointCards.add(networkEndpoint, "network");
        JPanel firstRow = transparent(new BorderLayout(16, 0));
        firstRow.add(field(text("连接方式", "Connection"), mode), BorderLayout.WEST);
        firstRow.add(field(endpointLabel, endpointCards));
        formCell(form, firstRow, 0, 0, 2, 1, new Insets(0, 0, 8, 0));
        JPanel folderField = transparent(new BorderLayout(8, 0));
        folderField.add(directory); folderField.add(browse, BorderLayout.EAST);
        JPanel secretField = transparent(new BorderLayout(8, 0));
        secretField.add(pairing); secretField.add(openPhone, BorderLayout.EAST);
        formCell(form, field(text("电脑文件夹", "Computer folder"), folderField), 0, 1, 1, 0.54, new Insets(0, 0, 0, 16));
        formCell(form, field(text("安全配对", "Secure pairing"), secretField), 1, 1, 1, 0.46, new Insets(0, 0, 0, 0));
        connection.add(form);

        JPanel connectionBottom = transparent(new BorderLayout(0, 8));
        JPanel preferences = transparent(new BorderLayout(12, 0));
        JPanel connectionPreferences = transparent(null);
        connectionPreferences.setLayout(new BoxLayout(connectionPreferences, BoxLayout.X_AXIS));
        connectionPreferences.add(reconnect);
        connectionPreferences.add(Box.createHorizontalStrut(12));
        connectionPreferences.add(launchPhone);
        preferences.add(connectionPreferences, BorderLayout.WEST);
        JButton more = new JButton(text("高级设置", "Advanced settings"));
        more.putClientProperty("JButton.buttonType", "borderless");
        more.setMargin(new Insets(6, 12, 6, 12));
        bindForeground(more, () -> BLUE);
        advancedPanel = advancedSettings();
        more.addActionListener(e -> showAdvancedSettings(advancedPanel));
        preferences.add(more, BorderLayout.EAST);
        connectionBottom.add(preferences, BorderLayout.NORTH);
        JPanel actions = transparent(new BorderLayout(16, 0));
        connect.setPreferredSize(new Dimension(148, 40));
        connect.setFont(connect.getFont().deriveFont(Font.BOLD));
        stop.setPreferredSize(new Dimension(120, 40));
        frame.getRootPane().setDefaultButton(connect);
        actions.add(line(connect, stop), BorderLayout.WEST);
        ReadOnlyText.configure(status, false); status.setOpaque(false);
        status.setLineWrap(true); status.setWrapStyleWord(true);
        status.setFont(status.getFont().deriveFont(12f));
        bindForeground(status, () -> MUTED);
        status.setBorder(new EmptyBorder(4, 0, 0, 0));
        status.setRows(2);
        actions.add(status);
        connectionBottom.add(actions);
        connection.add(connectionBottom, BorderLayout.SOUTH);
        addCard(cards, connection, 0, 0, 0, 0);

        Card transferCard = new Card(new BorderLayout(0, 10));
        JPanel transferHeading = transparent(new BorderLayout(12, 0));
        transferHeading.add(label(text("当前传输", "Current transfer"), 16f, true, TEXT), BorderLayout.WEST);
        transferBadge.state(text("等待任务", "Ready for a transfer"), () -> MUTED, () -> NEUTRAL_SOFT);
        transferHeading.add(transferBadge, BorderLayout.EAST);
        transferCard.add(transferHeading, BorderLayout.NORTH);
        JPanel transferBody = transparent(new BorderLayout(0, 8));
        JPanel progressHeading = transparent(new BorderLayout(12, 0));
        file.setText(text("连接后，在手机上选择文件开始传输", "Connect, then select files on your phone to begin"));
        file.setFont(file.getFont().deriveFont(13f)); bindForeground(file, () -> TEXT);
        progressHeading.add(file);
        progressPercent.setFont(progressPercent.getFont().deriveFont(Font.BOLD, 22f));
        bindForeground(progressPercent, () -> BLUE); progressPercent.setText("0%");
        progressHeading.add(progressPercent, BorderLayout.EAST);
        transferBody.add(progressHeading, BorderLayout.NORTH);
        progress.setStringPainted(false);
        progress.setPreferredSize(new Dimension(100, 10));
        progress.setString(text("尚无传输任务", "No transfer yet"));
        progress.addPropertyChangeListener("string", event -> progressCaption.setText(progress.getString()));
        transferBody.add(progress);
        JPanel transferFooter = transparent(new BorderLayout(12, 0));
        JPanel progressDetails = transparent(new GridLayout(2, 1, 0, 4));
        progressCaption.setText(text("文件校验与断点恢复已就绪", "File verification and resume are ready"));
        bindForeground(progressCaption, () -> MUTED);
        bindForeground(phase, () -> MUTED); phase.setFont(phase.getFont().deriveFont(12f));
        phase.setText(text("支持 USB 与 Wi-Fi 多通道传输", "Transfer over USB and Wi-Fi channels"));
        progressDetails.add(progressCaption); progressDetails.add(phase);
        transferFooter.add(progressDetails);
        cancelTransfer.setText(text("取消传输", "Cancel transfer"));
        bindForeground(cancelTransfer, () -> RED);
        transferFooter.add(cancelTransfer, BorderLayout.EAST);
        transferBody.add(transferFooter, BorderLayout.SOUTH);
        transferCard.add(transferBody);
        addCard(cards, transferCard, 1, 0, 0, 8);

        Card activityCard = new Card(new BorderLayout());
        activityCard.setBorder(new EmptyBorder(4, 16, 8, 16));
        activityCard.setPreferredSize(new Dimension(100, 145));
        JTabbedPane tabs = new JTabbedPane();
        tabs.setFocusable(true);
        tabs.putClientProperty("JTabbedPane.tabInsets", new Insets(8, 16, 8, 16));
        tabs.addTab(text("通道速度", "Channels"), tablePane(table(channels)));
        JPanel historyPanel = transparent(new BorderLayout(0, 8));
        historyPanel.add(tablePane(table(history)));
        rememberHistory.setText(text("在本机保留最近 100 次传输摘要", "Keep the latest 100 transfer summaries on this computer"));
        rememberHistory.setFont(rememberHistory.getFont().deriveFont(12f));
        bindForeground(rememberHistory, () -> MUTED);
        historyPanel.add(rememberHistory, BorderLayout.SOUTH);
        tabs.addTab(text("传输历史", "History"), historyPanel);
        JPanel diagnostic = transparent(new BorderLayout(0, 8));
        ReadOnlyText.configure(logs, true); logs.setLineWrap(false);
        // Logical fonts retain the platform font fallback needed by mixed Chinese/ASCII logs.
        logs.setFont(new Font(Font.DIALOG, Font.PLAIN, 13));
        bindForeground(logs, () -> TEXT); bindBackground(logs, () -> LOG_BACKGROUND);
        logs.setBorder(new EmptyBorder(12, 12, 12, 12));
        diagnostic.add(tablePane(logs));
        JButton saveLog = new JButton(text("保存日志", "Save log"));
        JButton clearLog = new JButton(text("清空", "Clear"));
        saveLog.putClientProperty("JButton.buttonType", "borderless");
        clearLog.putClientProperty("JButton.buttonType", "borderless");
        bindForeground(saveLog, () -> BLUE);
        saveLog.addActionListener(e -> saveLog()); clearLog.addActionListener(e -> logs.setText(""));
        diagnostic.add(line(saveLog, clearLog), BorderLayout.SOUTH);
        tabs.addTab(text("诊断日志", "Diagnostics"), diagnostic);
        activityCard.add(tabs);
        addCard(cards, activityCard, 2, 1, 1, 8);
        contentScroll = new JScrollPane(cards, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        contentScroll.setBorder(new EmptyBorder(0, 0, 0, 0));
        contentScroll.setOpaque(false); contentScroll.getViewport().setOpaque(false);
        contentScroll.getVerticalScrollBar().setUnitIncrement(24);
        root.add(contentScroll);
        root.add(label(text("在手机双栏界面选择文件；连接恢复后，由你继续传输。", "Choose files in the phone's two-pane browser. After reconnecting, you decide when to resume."),
                11f, false, MUTED), BorderLayout.SOUTH);
        frame.setContentPane(root);
        connectionControls.addAll(Arrays.asList(mode, devices, address, directory, port, localPort, pairing,
                refresh, browse, openPhone, reconnect, launchPhone, burst, conflict, verify, resume, incremental, prescan, more));
        connect.addActionListener(e -> begin());
        stop.addActionListener(e -> controller.close());
        cancelTransfer.addActionListener(e -> stopCurrentTask());
        mode.addActionListener(e -> modeChanged());
        refresh.addActionListener(e -> refreshDevices());
        browse.addActionListener(e -> chooseFolder());
        openPhone.addActionListener(e -> requestPhone());
        frame.addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent e) { closeWindow(); } });
    }

    private JPanel advancedSettings() {
        JPanel panel = transparent(new GridBagLayout());
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));
        row(panel, 0, text("同名文件", "Existing files"), conflict);
        row(panel, 1, text("文件保护", "File protection"), line(verify, resume));
        row(panel, 2, "", line(incremental, prescan));
        row(panel, 3, text("ADB 本地端口", "Local ADB port"), line(localPort, label(text("0 = 自动分配", "0 = automatic"), 12f, false, MUTED)));
        advancedPort = new PortField(port.getModel());
        advancedPort.setPreferredSize(new Dimension(112, 38));
        row(panel, 4, text("手机服务端口", "Phone service port"), advancedPort);
        row(panel, 5, "", burst);
        row(panel, 6, "", label(text("实验选项仅影响本程序启动的 ADB 进程。", "The experimental option only affects this app's ADB processes."), 12f, false, MUTED));
        row(panel, 7, "", label(text("CRC32 分块校验始终启用。SHA-256 较慢，可随时停止。",
                "CRC32 block checks are always on. SHA-256 takes time and can be stopped."), 12f, false, MUTED));
        verify.setToolTipText(text("任一端勾选即可；全部数据传完后并行校验，每轮结束后重发不匹配的文件。", "Enable on either device. Verify after all data arrives; resend mismatches after each round."));
        return panel;
    }

    private void showAdvancedSettings(JPanel advanced) {
        try { port.commitEdit(); localPort.commitEdit(); }
        catch (java.text.ParseException error) { showError(error); return; }
        while (true) {
            int result = JOptionPane.showConfirmDialog(frame, advanced,
                    text("传输与连接设置", "Transfer and connection settings"),
                    JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (result != JOptionPane.OK_OPTION) {
                advancedPort.setValue(port.getValue());
                localPort.setValue(localPort.getValue());
                return;
            }
            try { advancedPort.commitEdit(); localPort.commitEdit(); return; }
            catch (java.text.ParseException error) { showError(error); }
        }
    }

    private static JPanel field(String title, Component value) {
        return field(label(title, 12f, true, MUTED), value);
    }
    private static JPanel field(JLabel title, Component value) {
        JPanel panel = transparent(new BorderLayout(0, 6));
        title.setFont(title.getFont().deriveFont(Font.BOLD, 12f)); bindForeground(title, () -> MUTED);
        panel.add(title, BorderLayout.NORTH); panel.add(value);
        return panel;
    }
    private static void formCell(JPanel panel, Component value, int x, int y, int width, double weight, Insets insets) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx=x; c.gridy=y; c.gridwidth=width; c.weightx=weight; c.fill=GridBagConstraints.HORIZONTAL; c.insets=insets;
        panel.add(value, c);
    }
    private static void addCard(JPanel panel, JComponent value, int y, double weightx, double weighty, int top) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx=0; c.gridy=y; c.weightx=weightx == 0 ? 1 : weightx; c.weighty=weighty;
        c.fill=GridBagConstraints.BOTH; c.insets=new Insets(top, 0, 0, 0);
        panel.add(value, c);
    }
    private static JScrollPane tablePane(Component view) {
        JScrollPane pane = new JScrollPane(view);
        pane.setBorder(new EmptyBorder(4, 0, 0, 0));
        bindBackground(pane.getViewport(), () -> SURFACE);
        return pane;
    }
    private JTable table(DefaultTableModel model) {
        JTable table = new JTable(model) {
            @Override protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                if (getRowCount() == 0) {
                    Graphics2D g = (Graphics2D) graphics.create();
                    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g.setFont(getFont().deriveFont(13f)); g.setColor(MUTED);
                    String empty = model == channels ? text("连接后，实时通道速度会显示在这里", "Live channel speeds will appear here after connecting")
                            : text("完成一次传输后，即可在这里查看记录", "Your completed transfers will appear here");
                    int x = Math.max(12, (getWidth() - g.getFontMetrics().stringWidth(empty)) / 2);
                    g.drawString(empty, x, Math.max(40, getHeight() / 2));
                    g.dispose();
                }
            }
        };
        table.setRowHeight(32);
        table.setFillsViewportHeight(true);
        table.setAutoCreateRowSorter(true);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setShowGrid(false);
        bindForeground(table, () -> TEXT);
        table.getTableHeader().setPreferredSize(new Dimension(0, 28));
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setFont(table.getFont().deriveFont(Font.PLAIN, 12f));
        if (model == history) {
            table.getColumnModel().getColumn(0).setPreferredWidth(165);
            table.getColumnModel().getColumn(1).setPreferredWidth(130);
            table.getColumnModel().getColumn(2).setPreferredWidth(390);
            table.getColumnModel().getColumn(3).setPreferredWidth(100);
            table.getColumnModel().getColumn(4).setPreferredWidth(90);
        }
        return table;
    }
    private static JPanel line(Component... components) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0)); panel.setOpaque(false);
        for (Component component : components) panel.add(component);
        return panel;
    }
    private static void row(JPanel form, int row, String title, Component component) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = row; c.anchor = GridBagConstraints.WEST; c.insets = new Insets(6, 0, 6, 16);
        form.add(new JLabel(title), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        form.add(component, c);
    }
    private void modeChanged() {
        phoneRequestGeneration++;
        pendingPhonePairing = null;
        boolean adb = mode.getSelectedIndex() == 0;
        ((CardLayout) endpointCards.getLayout()).show(endpointCards, adb ? "adb" : "network");
        endpointLabel.setText(adb ? text("USB 设备", "USB device") : text("手机网络地址", "Phone network address"));
        devices.setEnabled(adb); refresh.setEnabled(adb); openPhone.setEnabled(adb && !phoneRequestInFlight);
        openPhone.setVisible(adb);
        launchPhone.setEnabled(adb); address.setEnabled(!adb); localPort.setEnabled(adb); burst.setEnabled(adb);
        pairing.putClientProperty("JTextField.placeholderText", adb
                ? text("自动配对，或输入手机配对码", "Automatic pairing, or enter a code")
                : text("输入手机上显示的完整配对码", "Enter the full code shown on the phone"));
    }
    private void setBusy(boolean busy) {
        for (JComponent control : connectionControls) control.setEnabled(!busy);
        connect.setEnabled(!busy && !phoneRequestInFlight); stop.setEnabled(busy);
        if (!busy) { modeChanged(); cancelTransfer.setEnabled(false); }
    }
    private String selectedDevice() {
        Object value = devices.getSelectedItem();
        return value instanceof AdbBridge.Device ? ((AdbBridge.Device) value).serial : null;
    }
    private String obtainSecret() {
        String secret = new String(pairing.getPassword()).trim();
        sessionSecret = secret;
        // Displaying the last automatically acquired code must not turn it into a
        // manual override on the next connection (the phone may have restarted).
        if (mode.getSelectedIndex() == 0 && secret.equals(automaticSecret)) return "";
        return secret;
    }
    private void displayPairing(String secret, int serverPort) {
        String entered = new String(pairing.getPassword()).trim();
        if (entered.isEmpty() || entered.equals(automaticSecret)) automaticSecret = secret;
        pairing.setText(secret);
        sessionSecret = secret;
        port.setValue(serverPort);
    }
    private void begin() {
        if (phoneRequestInFlight) return;
        phoneRequestGeneration++;
        try {
            port.commitEdit(); localPort.commitEdit();
            TransferOptions transfer = new TransferOptions();
            transfer.conflictPolicy = conflict.getSelectedIndex() == 0 ? TransferOptions.ConflictPolicy.KEEP_BOTH
                    : conflict.getSelectedIndex() == 1 ? TransferOptions.ConflictPolicy.SKIP : TransferOptions.ConflictPolicy.OVERWRITE;
            transfer.verifySha256 = verify.isSelected(); transfer.resume = resume.isSelected();
            transfer.incremental = incremental.isSelected(); transfer.prescan = prescan.isSelected();
            ConnectionController.Settings settings = new ConnectionController.Settings(mode.getSelectedIndex() == 0 ? "adb" : address.getText(),
                    selectedDevice(), directory.getText().trim(), obtainSecret(), (Integer) port.getValue(),
                    (Integer) localPort.getValue(), reconnect.isSelected(), mode.getSelectedIndex() == 0 && launchPhone.isSelected(), burst.isSelected(), transfer);
            settings.validate();
            AdbPairingSession preparation = "adb".equals(settings.address) && settings.secret.isEmpty()
                    && pendingPhonePairing != null && pendingPhonePairing.port() == settings.remotePort
                    && java.util.Objects.equals(pendingPhoneDevice, settings.device) ? pendingPhonePairing : null;
            pendingPhonePairing = null;
            try {
                if (prefs != null) { prefs.put("directory", settings.directory); prefs.put("address", address.getText().trim()); }
            } catch (SecurityException e) { appendLog(text("无法保存设置，本次连接仍可继续。", "Settings could not be saved; this connection can still continue.")); }
            setBusy(true);
            controller.start(settings, preparation);
        } catch (Exception e) { setBusy(false); showError(e); }
    }
    private void refreshDevices() {
        int generation = ++refreshGeneration;
        String selected = selectedDevice();
        refresh.setEnabled(false);
        utility.submit(() -> {
            if (closing) return;
            try (AdbBridge adb = new AdbBridge(false)) {
                auxiliaryAdb = adb;
                List<AdbBridge.Device> found = adb.devices();
                ui(() -> {
                    if (generation != refreshGeneration || controller.isRunning()) return;
                    devices.removeAllItems(); devices.addItem(text("自动选择唯一已授权手机", "Automatically select the only authorized phone"));
                    boolean retained = false;
                    for (AdbBridge.Device device : found) {
                        devices.addItem(device);
                        if (device.serial.equals(selected)) { devices.setSelectedItem(device); retained = true; }
                    }
                    if (selected != null && !retained) {
                        AdbBridge.Device missing = new AdbBridge.Device(selected, "offline", selected);
                        devices.addItem(missing); devices.setSelectedItem(missing);
                    }
                    appendLog(text("检测到设备：", "Devices detected: ") + found.size());
                });
            } catch (Exception e) { ui(() -> appendLog(ConnectionController.friendlyError(e))); }
            finally { auxiliaryAdb = null; ui(() -> refresh.setEnabled(!controller.isRunning() && mode.getSelectedIndex() == 0)); }
        });
    }
    private void requestPhone() {
        if (phoneRequestInFlight || controller.isRunning()) return;
        try { port.commitEdit(); }
        catch (java.text.ParseException error) { showError(error); return; }
        String selected = selectedDevice();
        String secret = obtainSecret();
        final String code = secret;
        final int serverPort = (Integer) port.getValue();
        final int generation = ++phoneRequestGeneration;
        final String enteredCode = new String(pairing.getPassword());
        final String enteredPort = port.getText();
        final boolean burstEnabled = burst.isSelected();
        phoneRequestInFlight = true;
        openPhone.setEnabled(false);
        connect.setEnabled(false);
        utility.submit(() -> {
            if (closing) return;
            try (AdbBridge adb = new AdbBridge(burstEnabled)) {
                auxiliaryAdb = adb;
                AdbBridge.Device device = ConnectionController.selectDevice(adb.devices(), selected);
                AdbPairingSession preparation = new AdbPairingSession(code, serverPort);
                boolean ready = preparation.prepare(adb, device.serial, true);
                ui(() -> {
                    if (!phoneRequestCurrent(generation, selected, enteredCode, enteredPort)) return;
                    if (code.isEmpty()) {
                        pendingPhonePairing = preparation;
                        pendingPhoneDevice = selected;
                    }
                    displayPairing(preparation.secret(), preparation.port());
                    updateState(ConnectionController.State.IDLE, ready && code.isEmpty()
                            ? text("已读取手机当前配对信息，点击连接即可。", "Current phone pairing is ready. Click Connect.")
                            : text("手机 App 已打开，请在手机上确认，然后点击连接。", "Phone app opened. Confirm on the phone, then connect."));
                });
            } catch (Exception e) { ui(() -> {
                if (phoneRequestCurrent(generation, selected, enteredCode, enteredPort)) showError(e);
            }); }
            finally { auxiliaryAdb = null; ui(() -> {
                phoneRequestInFlight = false;
                if (!closing) {
                    connect.setEnabled(!controller.isRunning());
                    openPhone.setEnabled(!controller.isRunning() && mode.getSelectedIndex() == 0);
                }
            }); }
        });
    }
    private boolean phoneRequestCurrent(int generation, String selected, String enteredCode, String enteredPort) {
        return !closing && generation == phoneRequestGeneration && !controller.isRunning()
                && mode.getSelectedIndex() == 0 && java.util.Objects.equals(selected, selectedDevice())
                && enteredCode.equals(new String(pairing.getPassword())) && enteredPort.equals(port.getText());
    }
    private void chooseFolder() {
        JFileChooser chooser = new JFileChooser(directory.getText());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) directory.setText(chooser.getSelectedFile().getAbsolutePath());
    }
    private void updateState(ConnectionController.State state, String message) {
        status.setText(message);
        bindForeground(status, () -> state == ConnectionController.State.ERROR ? RED : MUTED);
        String badge;
        java.util.function.Supplier<Color> foreground = () -> BLUE, background = () -> BLUE_SOFT;
        switch (state) {
            case CONNECTED:
                badge = text("已连接", "Connected"); foreground = () -> GREEN; background = () -> GREEN_SOFT; break;
            case TRANSFERRING:
                badge = text("正在传输", "Transferring"); foreground = () -> GREEN; background = () -> GREEN_SOFT; break;
            case VERIFYING:
                badge = text("文件校验", "Verifying"); break;
            case ERROR:
                badge = text("连接异常", "Connection issue"); foreground = () -> RED; background = () -> RED_SOFT; break;
            case STOPPING:
                badge = text("正在断开", "Disconnecting"); stop.setEnabled(false); break;
            case IDLE:
                badge = text("等待连接", "Ready to connect"); foreground = () -> MUTED; background = () -> NEUTRAL_SOFT; break;
            case WAITING:
                badge = text("等待设备", "Waiting for device"); break;
            default:
                badge = text("正在连接", "Connecting");
        }
        connectionBadge.state(badge, foreground, background);
    }
    private void started(boolean upload) {
        uploading = upload; taskStarted = System.currentTimeMillis(); taskFailed = false; transferActive = true; taskResult = ""; lastTransferredBytes = 0;
        transferResult = ""; verificationResult = ""; phase.setToolTipText(null);
        verificationActive = false; verificationStopRequested = false; taskFinished = false;
        cancelTransfer.setText(text("取消传输", "Cancel transfer"));
        transferBadge.state(upload ? text("电脑 → 手机", "Computer → Phone") : text("手机 → 电脑", "Phone → Computer"), () -> BLUE, () -> BLUE_SOFT);
        progressPercent.setText("…");
        bindForeground(progress, () -> BLUE);
        progress.setIndeterminate(true); progress.setString(text("正在准备传输…", "Preparing transfer…"));
        phase.setText(" "); file.setText(" "); cancelTransfer.setEnabled(true);
    }
    private void showProgress(TransferProgress value) {
        if (taskFinished) return;
        lastTransferredBytes = value.transferredBytes;
        if (verificationActive) return;
        if (value.totalBytes > 0) {
            progress.setIndeterminate(false);
            progress.setValue((int) Math.min(1000, value.transferredBytes * 1000.0 / value.totalBytes));
            progressPercent.setText(Math.min(100, (long)(value.transferredBytes * 100.0 / value.totalBytes)) + "%");
            progress.setString(size(value.transferredBytes) + " / " + size(value.totalBytes));
        } else {
            progress.setIndeterminate(transferActive);
            progressPercent.setText("…");
            progress.setString(size(value.transferredBytes) + text(" 已完成（总量未预先统计）", " completed (total not pre-counted)"));
        }
        phase.setText(text("已完成文件：", "Completed files: ") + value.completedFiles
                + (value.totalFiles >= 0 ? " / " + value.totalFiles : "")
                + "   ·   " + duration(System.currentTimeMillis() - taskStarted));
        file.setText(value.currentPath == null ? " " : value.currentPath);
        file.setToolTipText(value.currentPath);
    }
    private void flushProgress() {
        TransferProgress value = latestProgress.getAndSet(null);
        if (value != null) showProgress(value);
        VerificationProgress verification = latestVerification.getAndSet(null);
        if (verification != null) showVerification(verification);
    }
    private void showVerification(VerificationProgress value) {
        if (!transferActive || taskFinished) return;
        verificationActive = true;
        verificationStopRequested |= "stopping".equals(value.phase);
        cancelTransfer.setText(text("停止校验与重试", "Stop verification & retries"));
        cancelTransfer.setEnabled(!verificationStopRequested);
        boolean retrying = "retrying".equals(value.phase);
        String heading = verificationStopRequested ? text("正在停止校验…", "Stopping verification…")
                : retrying ? text("正在重发不匹配的文件", "Resending mismatched files")
                : "waiting-peer".equals(value.phase) ? text("等待对端校验", "Waiting for peer verification")
                : text("正在校验 SHA-256", "Verifying SHA-256");
        transferBadge.state(heading, () -> BLUE, () -> BLUE_SOFT);
        bindForeground(progress, () -> BLUE);
        progress.setIndeterminate(retrying || verificationStopRequested || value.totalFiles == 0);
        if (!progress.isIndeterminate()) {
            progress.setValue((int) Math.min(1000, value.checkedFiles * 1000.0 / value.totalFiles));
        }
        progressPercent.setText(value.checkedFiles + " / " + value.totalFiles);
        progress.setString(heading + text(" · 第 ", " · Round ") + value.round + text(" 轮", ""));
        phase.setText(text("本轮已校验 ", "Checked this round ") + value.checkedFiles
                + text(" · 通过 ", " · passed ") + value.verifiedFiles
                + text(" · 失败 ", " · failed ") + value.failedFiles
                + text(" · 未校验 ", " · unchecked ") + Math.max(0, value.totalFiles - value.checkedFiles));
        file.setText(value.currentPath == null ? " " : value.currentPath);
        file.setToolTipText(value.currentPath);
    }
    private void stopCurrentTask() {
        if (!transferActive || taskFinished) return;
        // Consume a pending first verification event before deciding which cancellation applies.
        flushProgress();
        cancelTransfer.setEnabled(false);
        if (verificationActive) {
            verificationStopRequested = true;
            controller.stopVerification();
            progress.setString(text("正在停止校验与重试…", "Stopping verification and retries…"));
        } else {
            controller.cancelTransfer();
            phase.setText(text("正在安全停止传输…", "Stopping the transfer safely…"));
        }
    }
    private void flushLogs() {
        StringBuilder batch = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            String message = pendingLogs.poll();
            if (message == null) break;
            pendingLogCount.decrementAndGet();
            if (batch.length() > 0) batch.append('\n');
            batch.append(message);
        }
        int omitted = omittedLogs.getAndSet(0);
        if (omitted > 0) batch.append("\n").append(omitted).append(text(" 条重复诊断事件未显示；传输结果见摘要。", " diagnostic events omitted from the display; see the transfer summary."));
        if (batch.length() > 0) appendLog(batch.toString());
    }
    private void updateSpeeds(List<TrafficInfo> values) {
        for (TrafficInfo value : values) {
            for (int row = 0; row < channels.getRowCount(); row++) {
                if (value.iName.equals(channels.getValueAt(row, 0))) {
                    channels.setValueAt(size(value.uploadTraffic) + "/s", row, 1);
                    channels.setValueAt(size(value.downloadTraffic) + "/s", row, 2);
                    break;
                }
            }
        }
    }
    private void showSummary(TransferSummary summary) {
        lastTransferredBytes = summary.transferredBytes;
        taskFailed |= summary.failedFiles > 0 || summary.cancelled;
        taskResult = text("完成 ", "Completed ") + summary.completedFiles + text(" · 跳过 ", " · skipped ") + summary.skippedFiles
                + text(" · 失败 ", " · failed ") + summary.failedFiles
                + (summary.warnings.isEmpty() ? "" : text(" · 提醒 ", " · warnings ") + summary.warnings.size())
                + (summary.cancelled ? text(" · 已取消", " · cancelled") : "");
        transferResult = taskResult;
        if (summary.verificationEnabled) {
            verificationResult = text("SHA 通过 ", "SHA passed ") + summary.verifiedFiles
                    + text(" / 失败 ", " / failed ") + summary.verificationFailedFiles
                    + text(" / 未校验 ", " / unchecked ") + summary.unverifiedFiles
                    + text(" · 重发 ", " · resent ") + summary.retriedFiles;
            taskResult += " · " + verificationResult + text(" · 校验轮数 ", " · rounds ") + summary.verificationRounds
                    + (summary.verificationStopped ? text(" · 已停止校验", " · verification stopped") : "");
        } else {
            verificationResult = text("未启用 SHA-256", "SHA-256 disabled");
            taskResult += " · " + verificationResult;
        }
        phase.setText(verificationResult);
        phase.setToolTipText(taskResult);
        appendLog(taskResult);
        for (String failure : summary.failures) appendLog(failure);
        for (String warning : summary.warnings) appendLog(text("提醒：", "Warning: ") + warning);
    }
    private void completed(boolean upload, long bytes, long millis) {
        taskFinished = true; latestProgress.set(null); latestVerification.set(null);
        transferActive = false; cancelTransfer.setEnabled(false); progress.setIndeterminate(false);
        if (!taskFailed) progress.setValue(1000);
        progressPercent.setText(taskFailed ? text("未完成", "Incomplete") : "100%");
        final boolean failed = taskFailed;
        bindForeground(progress, () -> failed ? RED : GREEN);
        transferBadge.state(taskFailed ? text("未完成", "Incomplete") : text("传输完成", "Completed"), () -> failed ? RED : GREEN, () -> failed ? RED_SOFT : GREEN_SOFT);
        String result = taskResult.isEmpty() ? taskFailed ? text("未完成", "Incomplete") : text("完成", "Completed") : taskResult;
        progress.setString(transferResult.isEmpty() ? result : transferResult);
        phase.setText(verificationResult.isEmpty() ? size(bytes) + " · " + duration(millis) : verificationResult);
        addHistory(upload, result, bytes, millis);
        Toolkit.getDefaultToolkit().beep();
    }
    private void verificationStopped(boolean upload, long bytes, long millis) {
        taskFinished = true; transferActive = false;
        latestProgress.set(null); latestVerification.set(null);
        cancelTransfer.setEnabled(false); progress.setIndeterminate(false);
        progressPercent.setText(text("已停止", "Stopped"));
        bindForeground(progress, () -> BLUE);
        transferBadge.state(text("校验已停止", "Verification stopped"), () -> BLUE, () -> BLUE_SOFT);
        progress.setString(text("已保留接收文件，请查看校验摘要", "Received files retained; review the verification summary"));
        phase.setText(verificationResult);
        addHistory(upload, taskResult, bytes, millis);
    }
    private void failed(String message) {
        taskFinished = true; latestProgress.set(null); latestVerification.set(null);
        taskFailed = true;
        transferBadge.state(text("传输未完成", "Incomplete"), () -> RED, () -> RED_SOFT);
        bindForeground(progress, () -> RED);
        appendLog(message);
        phase.setText(message);
        if (transferActive) {
            transferActive = false; progress.setIndeterminate(false); progress.setString(text("未完成", "Incomplete"));
            addHistory(uploading, taskResult.isEmpty() ? text("未完成", "Incomplete") : taskResult, lastTransferredBytes, System.currentTimeMillis() - taskStarted);
        }
        cancelTransfer.setEnabled(false);
    }
    private void addHistory(boolean upload, String result, long bytes, long millis) {
        String[] row = {LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                upload ? text("电脑 → 手机", "Computer → Phone") : text("手机 → 电脑", "Phone → Computer"),
                result, size(bytes), duration(millis)};
        historyRows.add(0, row); history.insertRow(0, row);
        while (historyRows.size() > 100) historyRows.remove(historyRows.size() - 1);
        while (history.getRowCount() > 100) history.removeRow(history.getRowCount() - 1);
        if (rememberHistory.isSelected()) {
            List<String[]> snapshot = new ArrayList<>(historyRows);
            utility.submit(() -> { try { historyStore.save(snapshot); } catch (Exception e) { ui(() -> appendLog("History: " + e.getMessage())); } });
        }
    }
    private void appendLog(String message) {
        if (message == null) return;
        if (!sessionSecret.isEmpty()) message = message.replace(sessionSecret, "[redacted]");
        logs.append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "  " + message + "\n");
        if (logs.getDocument().getLength() > 200000) logs.replaceRange("", 0, logs.getDocument().getLength() - 160000);
        logs.setCaretPosition(logs.getDocument().getLength());
    }
    private void saveLog() {
        flushLogs();
        Path output = NativeFileDialogs.chooseSaveFile(frame, text("保存诊断日志", "Save diagnostic log"),
                Paths.get(System.getProperty("user.home"), "HybridFileXfer-diagnostics.txt"));
        if (output == null) return;
        String content = logs.getText();
        utility.submit(() -> {
            try { Files.write(output, content.getBytes(StandardCharsets.UTF_8)); ui(() -> appendLog(text("日志已保存。", "Log saved."))); }
            catch (Exception e) { ui(() -> showError(e)); }
        });
    }
    private void showError(Throwable error) {
        String message = ConnectionController.friendlyError(error);
        appendLog(message);
        JOptionPane.showMessageDialog(frame, message, text("请检查连接设置", "Check connection settings"), JOptionPane.WARNING_MESSAGE);
    }
    private void closeWindow() {
        if (transferActive && JOptionPane.showConfirmDialog(frame, text("当前传输将停止。关闭窗口？", "The active transfer will stop. Close the window?"),
                "HybridFileXfer", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        closing = true;
        AdbBridge adb = auxiliaryAdb; if (adb != null) adb.close();
        if (controller.isRunning()) controller.close(); else dispose();
    }
    private void dispose() {
        if (disposalStarted) return;
        disposalStarted = true;
        progressTimer.stop();
        themeMonitor.close();
        pairing.setText(""); sessionSecret = ""; automaticSecret = "";
        // Drain queued local history writes before removing the last AWT window.
        utility.submit(() -> SwingUtilities.invokeLater(frame::dispose));
        utility.shutdown();
    }
    private void ui(Runnable action) { SwingUtilities.invokeLater(() -> { if (frame.isDisplayable() || !closing) action.run(); }); }

    /** Used by the visual smoke test without connecting to a real device. */
    public JFrame windowForTesting() { return frame; }
}
