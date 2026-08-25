package com.kmultan.claims.api;

import com.kmultan.claims.domain.auth.Role;
import com.kmultan.claims.domain.auth.UserAccount;
import com.kmultan.claims.domain.auth.UserAccountRepository;
import com.kmultan.claims.infrastructure.security.AuthenticatedUser;
import com.kmultan.claims.infrastructure.security.JwtTokenService;
import com.kmultan.platform.web.ProblemDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final UserAccountRepository accounts;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService tokenService;

    public AuthController(UserAccountRepository accounts, PasswordEncoder passwordEncoder, JwtTokenService tokenService) {
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
    public record UserInfo(String username, String displayName, Set<Role> roles) {}
    public record LoginResponse(String accessToken, String tokenType, Instant expiresAt, UserInfo user) {}

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        Optional<UserAccount> account = accounts.findByUsername(request.username().trim().toLowerCase())
                .filter(UserAccount::isEnabled)
                .filter(candidate -> passwordEncoder.matches(request.password(), candidate.getPasswordHash()));
        if (account.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ProblemDetails.of(HttpStatus.UNAUTHORIZED, "Login failed", "Wrong username or password"));
        }
        UserAccount authenticated = account.get();
        JwtTokenService.IssuedToken issued = tokenService.issue(authenticated);
        return ResponseEntity.ok(new LoginResponse(issued.value(), "Bearer", issued.expiresAt(),
                new UserInfo(authenticated.getUsername(), authenticated.getDisplayName(), authenticated.getRoles())));
    }

    @GetMapping("/me")
    public UserInfo me() {
        AuthenticatedUser user = AuthenticatedUser.current();
        String displayName = accounts.findById(user.id()).map(UserAccount::getDisplayName).orElse(user.username());
        return new UserInfo(user.username(), displayName, user.roles());
    }
}
