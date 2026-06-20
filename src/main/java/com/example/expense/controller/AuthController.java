package com.example.expense.controller;

import java.net.URI;

import com.example.expense.dto.request.LoginRequest;
import com.example.expense.dto.request.RefreshTokenRequest;
import com.example.expense.dto.request.SignupRequest;
import com.example.expense.dto.response.AuthResponse;
import com.example.expense.dto.response.UserResponse;
import com.example.expense.entity.User;
import com.example.expense.service.AuthService;
import com.example.expense.service.RefreshTokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final RefreshTokenService refreshTokenService;

    public AuthController(AuthService authService, RefreshTokenService refreshTokenService) {
        this.authService = authService;
        this.refreshTokenService = refreshTokenService;
    }

    @PostMapping("/signup")
    public ResponseEntity<UserResponse> signup(@Valid @RequestBody SignupRequest req) {
        User user = authService.signup(req);
        return ResponseEntity.created(URI.create("/api/users/" + user.getId()))
                .body(UserResponse.from(user));
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        return authService.login(req, clientIp(http));
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshTokenRequest req) {
        return refreshTokenService.rotate(req.refreshToken());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshTokenRequest req) {
        refreshTokenService.revoke(req.refreshToken());
        return ResponseEntity.noContent().build();
    }
}
