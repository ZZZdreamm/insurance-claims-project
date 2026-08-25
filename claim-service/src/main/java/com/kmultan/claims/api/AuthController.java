package com.kmultan.claims.api;

import com.kmultan.claims.domain.auth.Role;
import com.kmultan.claims.domain.auth.UserAccount;
import com.kmultan.claims.domain.auth.UserAccountRepository;
import com.kmultan.claims.infrastructure.security.CurrentUser;
import com.kmultan.claims.infrastructure.security.JwtTokens;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
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

    private final UserAccountRepository users;
    private final PasswordEncoder encoder;
    private final JwtTokens tokens;

    public AuthController(UserAccountRepository users, PasswordEncoder encoder, JwtTokens tokens) {
        this.users = users;
        this.encoder = encoder;
        this.tokens = tokens;
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
    public record UserInfo(String username, String displayName, Set<Role> roles) {}
    public record LoginResponse(String accessToken, String tokenType, Instant expiresAt, UserInfo user) {}

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req) {
        Optional<UserAccount> user = users.findByUsername(req.username().trim().toLowerCase())
                .filter(UserAccount::isEnabled)
                .filter(u -> encoder.matches(req.password(), u.getPasswordHash()));
        if (user.isEmpty()) {
            ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Wrong username or password");
            pd.setTitle("Login failed");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(pd);
        }
        UserAccount u = user.get();
        JwtTokens.Issued issued = tokens.issue(u);
        return ResponseEntity.ok(new LoginResponse(issued.token(), "Bearer", issued.expiresAt(),
                new UserInfo(u.getUsername(), u.getDisplayName(), u.getRoles())));
    }

    @GetMapping("/me")
    public UserInfo me() {
        CurrentUser me = CurrentUser.get();
        String display = users.findById(me.id()).map(UserAccount::getDisplayName).orElse(me.username());
        return new UserInfo(me.username(), display, me.roles());
    }
}
