package com.example.expense.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;

import com.example.expense.dto.response.AuthResponse;
import com.example.expense.entity.RefreshToken;
import com.example.expense.entity.Role;
import com.example.expense.entity.User;
import com.example.expense.exception.UnauthorizedException;
import com.example.expense.repository.RefreshTokenRepository;
import com.example.expense.repository.UserRepository;
import com.example.expense.security.JwtTokenProvider;
import com.example.expense.security.RefreshReplayStore;
import com.example.expense.security.TokenHashing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private UserRepository userRepository;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private RefreshReplayStore replayStore;

    private RefreshTokenService service;

    @BeforeEach
    void setup() {
        service = new RefreshTokenService(refreshTokenRepository, userRepository,
                jwtTokenProvider, replayStore, 604800);
    }

    private User user() {
        User u = org.mockito.Mockito.mock(User.class);
        when(u.getId()).thenReturn(2L);
        when(u.getRole()).thenReturn(Role.USER);
        return u;
    }

    private RefreshToken activeToken() {
        return new RefreshToken(2L, "hash", OffsetDateTime.now().plusDays(7));
    }

    @Test
    void rotateHappyPathIssuesNewPairAndRevokesOld() {
        String presented = "old-plain";
        String hash = TokenHashing.sha256Hex(presented);
        RefreshToken old = activeToken();

        User user = user();
        when(replayStore.get(hash)).thenReturn(Optional.empty());
        when(refreshTokenRepository.findByTokenHashForUpdate(hash)).thenReturn(Optional.of(old));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user));
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(i -> i.getArgument(0));
        when(jwtTokenProvider.generateAccessToken(any())).thenReturn("access-jwt");
        when(jwtTokenProvider.getAccessTtlSeconds()).thenReturn(900L);

        AuthResponse res = service.rotate(presented);

        assertThat(res.accessToken()).isEqualTo("access-jwt");
        assertThat(res.refreshToken()).isNotBlank().isNotEqualTo(presented);
        assertThat(old.isRevoked()).isTrue();
        verify(replayStore).put(eq(hash), any(AuthResponse.class));
    }

    @Test
    void rotateReturnsCachedPairOnReplayHit() {
        String presented = "old-plain";
        String hash = TokenHashing.sha256Hex(presented);
        AuthResponse cached = AuthResponse.bearer("a", "r", 900, 2L, Role.USER);
        when(replayStore.get(hash)).thenReturn(Optional.of(cached));

        AuthResponse res = service.rotate(presented);

        assertThat(res).isEqualTo(cached);
        verify(refreshTokenRepository, never()).findByTokenHashForUpdate(anyString());
    }

    @Test
    void rotateDetectsReuseAndRevokesAllAfterGrace() {
        String presented = "stolen";
        String hash = TokenHashing.sha256Hex(presented);
        RefreshToken revoked = activeToken();
        revoked.revoke();

        // 最初の replay get (grace判定前) と revoked 内の再確認、どちらも miss
        when(replayStore.get(hash)).thenReturn(Optional.empty());
        when(refreshTokenRepository.findByTokenHashForUpdate(hash)).thenReturn(Optional.of(revoked));

        assertThatThrownBy(() -> service.rotate(presented))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("reuse");
        verify(refreshTokenRepository).revokeAllByUserId(eq(2L), any(OffsetDateTime.class));
    }

    @Test
    void rotateConcurrentRetryReturnsRacedWinnerInsteadOfTheft() {
        String presented = "old-plain";
        String hash = TokenHashing.sha256Hex(presented);
        RefreshToken revoked = activeToken();
        revoked.revoke();
        AuthResponse winner = AuthResponse.bearer("a2", "r2", 900, 2L, Role.USER);

        // 1回目(grace判定前)=miss、2回目(revoked内の再確認)=hit
        when(replayStore.get(hash)).thenReturn(Optional.empty(), Optional.of(winner));
        when(refreshTokenRepository.findByTokenHashForUpdate(hash)).thenReturn(Optional.of(revoked));

        AuthResponse res = service.rotate(presented);

        assertThat(res).isEqualTo(winner);
        verify(refreshTokenRepository, never()).revokeAllByUserId(anyLong(), any());
    }

    @Test
    void rotateRejectsUnknownToken() {
        String presented = "nope";
        String hash = TokenHashing.sha256Hex(presented);
        when(replayStore.get(hash)).thenReturn(Optional.empty());
        when(refreshTokenRepository.findByTokenHashForUpdate(hash)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rotate(presented))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("Invalid");
    }

    @Test
    void revokeMarksTokenRevoked() {
        RefreshToken token = activeToken();
        when(refreshTokenRepository.findByTokenHash(TokenHashing.sha256Hex("p"))).thenReturn(Optional.of(token));

        service.revoke("p");

        assertThat(token.isRevoked()).isTrue();
    }
}
