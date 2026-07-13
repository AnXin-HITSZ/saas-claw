package com.anxin.pyclaw.backend.auth;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/register")
    public LoginResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @GetMapping("/me")
    public MeResponse me(Authentication authentication) {
        AuthenticatedPrincipal principal = (AuthenticatedPrincipal) authentication.getPrincipal();
        List<String> authorities = authentication.getAuthorities().stream()
                .map(Object::toString)
                .toList();
        return new MeResponse(principal.userId(), principal.getUsername(), principal.actorType(), authorities);
    }
}
