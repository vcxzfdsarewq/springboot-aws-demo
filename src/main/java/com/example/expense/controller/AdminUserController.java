package com.example.expense.controller;

import com.example.expense.dto.request.UpdateRoleRequest;
import com.example.expense.dto.response.UserResponse;
import com.example.expense.service.UserService;
import com.example.expense.web.CurrentUserProvider;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 管理者向けユーザー管理。/api/admin/** は SecurityConfig で ADMIN 限定。 */
@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final UserService userService;
    private final CurrentUserProvider currentUser;

    public AdminUserController(UserService userService, CurrentUserProvider currentUser) {
        this.userService = userService;
        this.currentUser = currentUser;
    }

    /** ユーザーのロール変更 (昇格/降格)。 */
    @PutMapping("/{id}/role")
    public UserResponse changeRole(@PathVariable Long id, @Valid @RequestBody UpdateRoleRequest req) {
        return UserResponse.from(
                userService.changeRole(currentUser.requireId(), id, req.role()));
    }
}
