package com.example.expense.controller;

import com.example.expense.dto.response.PagedResponse;
import com.example.expense.dto.response.UserResponse;
import com.example.expense.service.UserService;
import com.example.expense.web.CurrentUserProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;
    private final CurrentUserProvider currentUser;

    public UserController(UserService userService, CurrentUserProvider currentUser) {
        this.userService = userService;
        this.currentUser = currentUser;
    }

    /** 自分のプロフィール (USER, ADMIN)。 */
    @GetMapping("/me")
    public UserResponse me() {
        return UserResponse.from(userService.getById(currentUser.requireId()));
    }

    /** ユーザー一覧 (ADMIN のみ)。 */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public PagedResponse<UserResponse> list(@PageableDefault(size = 20) Pageable pageable) {
        Page<UserResponse> page = userService.list(pageable).map(UserResponse::from);
        return PagedResponse.from(page);
    }
}
