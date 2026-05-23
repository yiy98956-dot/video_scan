package com.videoplatform.infrastructure.gui;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.net.URI;

@Slf4j
public class ServerGUI {

    private static ConfigurableApplicationContext context;

    public static void showGUI(ConfigurableApplicationContext ctx) {
        context = ctx;
        
        // 设置外观为系统默认
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            log.error("Failed to set LookAndFeel", e);
        }

        // 确保在主线程执行 Swing UI
        SwingUtilities.invokeLater(() -> {
            if (!SystemTray.isSupported()) {
                showSimpleFrame();
                return;
            }
            setupTrayIcon();
            showSimpleFrame();
        });
    }

    private static void setupTrayIcon() {
        try {
            SystemTray tray = SystemTray.getSystemTray();
            
            // 创建一个简单的图标（如果没有图标文件，可以用一个绘制的图像）
            Image image = Toolkit.getDefaultToolkit().createImage(new byte[0]); // 占位符
            // 实际应用中可以加载一个 logo.png
            
            PopupMenu popup = new PopupMenu();
            
            MenuItem openItem = new MenuItem("打开管理后台");
            openItem.addActionListener(e -> openBrowser("http://localhost:8080"));
            
            MenuItem stopItem = new MenuItem("停止并退出");
            stopItem.addActionListener(e -> {
                if (context != null) {
                    SpringApplication.exit(context, () -> 0);
                }
                System.exit(0);
            });

            popup.add(openItem);
            popup.addSeparator();
            popup.add(stopItem);

            TrayIcon trayIcon = new TrayIcon(image, "视频平台后端服务", popup);
            trayIcon.setImageAutoSize(true);
            tray.add(trayIcon);
            
            log.info("System Tray Icon initialized.");
        } catch (Exception e) {
            log.error("Failed to setup Tray Icon", e);
        }
    }

    private static void showSimpleFrame() {
        JFrame frame = new JFrame("视频平台后端管理");
        frame.setSize(400, 250);
        frame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        frame.setLayout(new BorderLayout(10, 10));
        frame.setLocationRelativeTo(null); // 居中

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        JLabel statusLabel = new JLabel("服务器状态: 正在运行");
        statusLabel.setFont(new Font("微软雅黑", Font.BOLD, 16));
        statusLabel.setForeground(new Color(0, 150, 0));
        statusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel infoLabel = new JLabel("接口地址: http://localhost:8080");
        infoLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        JButton btnOpen = new JButton("进入管理系统");
        btnOpen.setAlignmentX(Component.CENTER_ALIGNMENT);
        btnOpen.addActionListener(e -> openBrowser("http://localhost:8080"));

        JButton btnStop = new JButton("停止服务器");
        btnStop.setAlignmentX(Component.CENTER_ALIGNMENT);
        btnStop.setBackground(new Color(200, 0, 0));
        btnStop.setForeground(Color.WHITE);
        btnStop.addActionListener(e -> {
            int result = JOptionPane.showConfirmDialog(frame, "确定要停止服务器吗？", "确认", JOptionPane.YES_NO_OPTION);
            if (result == JOptionPane.YES_OPTION) {
                if (context != null) {
                    SpringApplication.exit(context, () -> 0);
                }
                System.exit(0);
            }
        });

        panel.add(statusLabel);
        panel.add(Box.createVerticalStrut(10));
        panel.add(infoLabel);
        panel.add(Box.createVerticalStrut(20));
        panel.add(btnOpen);
        panel.add(Box.createVerticalStrut(10));
        panel.add(btnStop);

        frame.add(panel, BorderLayout.CENTER);
        frame.setVisible(true);
    }

    private static void openBrowser(String url) {
        try {
            Desktop.getDesktop().browse(new URI(url));
        } catch (Exception e) {
            log.error("Failed to open browser", e);
        }
    }
}
