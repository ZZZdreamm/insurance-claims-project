package com.kmultan.claims.api.admin;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kmultan.claims.domain.auth.Role;
import com.kmultan.claims.domain.auth.UserAccount;
import com.kmultan.claims.domain.auth.UserAccountRepository;
import com.kmultan.claims.infrastructure.security.AuthenticatedUser;

/** Account administration. Admins cannot lock themselves out. */
@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final UserAccountRepository accounts;
    private final PasswordEncoder passwordEncoder;

    public AdminUserController(UserAccountRepository accounts, PasswordEncoder passwordEncoder) {
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
    }

    public record UserResponse(
            UUID id, String username, String displayName, Set<Role> roles, boolean enabled, Instant createdAt) {
        static UserResponse from(UserAccount account) {
            return new UserResponse(
                    account.getId(),
                    account.getUsername(),
                    account.getDisplayName(),
                    account.getRoles(),
                    account.isEnabled(),
                    account.getCreatedAt());
        }
    }

    public record CreateUserRequest(
            @NotBlank
                    @Size(min = 3, max = 64)
                    @Pattern(regexp = "^[a-z0-9._-]+$", message = "lowercase letters, digits, '.', '_' or '-'")
                    String username,
            @NotBlank @Size(min = 4, max = 128) String password,
            @NotBlank @Size(max = 120) String displayName,
            @NotEmpty Set<Role> roles) {}

    public record UpdateUserRequest(
            Set<Role> roles,
            Boolean enabled,
            @Size(min = 4, max = 128) String password,
            @Size(max = 120) String displayName) {}

    @GetMapping
    public List<UserResponse> list() {
        return accounts.findAll().stream()
                .sorted((left, right) -> left.getUsername().compareTo(right.getUsername()))
                .map(UserResponse::from)
                .toList();
    }

    @PostMapping
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        if (accounts.findByUsername(request.username()).isPresent()) {
            throw new IllegalStateException("Username already taken: " + request.username());
        }
        UserAccount account = new UserAccount(
                request.username(), passwordEncoder.encode(request.password()), request.displayName(), request.roles());
        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(accounts.save(account)));
    }

    @PatchMapping("/{userId}")
    @Transactional
    public UserResponse update(@PathVariable UUID userId, @Valid @RequestBody UpdateUserRequest request) {
        UserAccount account =
                accounts.findById(userId).orElseThrow(() -> new IllegalArgumentException("No such user: " + userId));
        boolean self = AuthenticatedUser.current().id().equals(userId);
        if (request.roles() != null) {
            if (self && !request.roles().contains(Role.ADMIN)) {
                throw new IllegalStateException("You cannot remove your own ADMIN role");
            }
            account.changeRoles(request.roles());
        }
        if (request.enabled() != null) {
            if (self && !request.enabled()) {
                throw new IllegalStateException("You cannot disable your own account");
            }
            account.setEnabled(request.enabled());
        }
        if (request.displayName() != null) {
            account.rename(request.displayName());
        }
        if (request.password() != null) {
            account.changePassword(passwordEncoder.encode(request.password()));
        }
        return UserResponse.from(account);
    }
}
