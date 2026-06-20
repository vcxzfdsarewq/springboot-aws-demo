package com.example.expense.dto.request;

import com.example.expense.entity.Role;
import jakarta.validation.constraints.NotNull;

public record UpdateRoleRequest(

        @NotNull
        Role role
) {
}
