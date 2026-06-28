package com.example.myspringai_mcp_client;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MySpringAiMcpClientApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(MySpringAiMcpClientApplication.class);

        // 1. 讀取 JVM 的系統屬性
        String os = System.getProperty("os.name").toLowerCase();

        if (os.contains("windows")) {
            app.setAdditionalProfiles("windows"); // 額外啟動名為 windows 的 Profile。Spring Boot 啟動時會自動去找並載入 application-windows.properties，把裡面的設定合併進來。
        } else if (os.contains("mac")) {
            app.setAdditionalProfiles("mac"); // 額外啟動名為 mac 的 Profile。Spring Boot 啟動時會自動去找並載入 application-mac.properties，把裡面的設定合併進來。
        }
        app.run(args);
    }

}
