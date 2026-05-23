package com.videoplatform;

import com.videoplatform.infrastructure.gui.ServerGUI;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableAsync;

import java.awt.*;

@SpringBootApplication
@EnableAsync
@MapperScan("com.videoplatform.**.mapper")
public class VideoPlatformApplication {

    public static void main(String[] args) {
        // 自动探测是否支持图形环境
        boolean isHeadless = GraphicsEnvironment.isHeadless();
        
        ConfigurableApplicationContext context = new SpringApplicationBuilder(VideoPlatformApplication.class)
                .headless(isHeadless)
                .run(args);

        // 仅在有图形环境时显示 GUI
        if (!isHeadless) {
            ServerGUI.showGUI(context);
        } else {
            System.out.println(">>> 检测到无界面环境 (Headless Mode)，已跳过管理面板显示。");
            System.out.println(">>> 服务已启动: http://localhost:8080");
        }
    }
}
