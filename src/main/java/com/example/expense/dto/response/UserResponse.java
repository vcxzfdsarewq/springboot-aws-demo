package com.example.expense.dto.response;

import java.time.OffsetDateTime;

import com.example.expense.entity.Role;
import com.example.expense.entity.User;

public record UserResponse(
        Long id,
        String email,
        String name,
        Role role,
        OffsetDateTime createdAt
) {
    public static UserResponse from(User u) {
        return new UserResponse(u.getId(), u.getEmail(), u.getName(), u.getRole(), u.getCreatedAt());
    }
}
