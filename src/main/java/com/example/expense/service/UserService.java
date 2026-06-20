package com.example.expense.service;

import com.example.expense.entity.Role;
import com.example.expense.entity.User;
import com.example.expense.exception.ResourceNotFoundException;
import com.example.expense.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final RefreshTokenService refreshTokenService;

    public UserService(UserRepository userRepository, RefreshTokenService refreshTokenService) {
        this.userRepository = userRepository;
        this.refreshTokenService = refreshTokenService;
    }

    @Transactional(readOnly = true)
    public User getById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + id));
    }

    @Transactional(readOnly = true)
    public Page<User> list(Pageable pageable) {
        return userRepository.findAll(pageable);
    }

    /** ロール変更 (ADMIN のみが呼ぶ)。監査ログに記録する。 */
    @Transactional
    public User changeRole(Long actorAdminId, Long targetUserId, Role newRole) {
        User user = getById(targetUserId);
        Role old = user.getRole();
        user.setRole(newRole);
        // 既存の Refresh Token を全失効し、新しい権限のトークンで再ログインさせる。
        // (発行済み Access Token は最大 15 分で失効するまで旧権限が残る点は許容)
        int revoked = refreshTokenService.revokeAllForUser(targetUserId);
        log.info("AUDIT role-change: adminId={} targetUserId={} {} -> {} (revoked {} refresh tokens)",
                actorAdminId, targetUserId, old, newRole, revoked);
        return user;
    }
}
