package com.example.expense;

import java.util.TimeZone;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ExpenseApplication {

    public static void main(String[] args) {
        // 設計の日時型ポリシー: アプリ全体を UTC で動かす (-Duser.timezone=UTC 相当)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(ExpenseApplication.class, args);
    }

    @PostConstruct
    void enforceUtc() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }
}
