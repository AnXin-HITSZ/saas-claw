package com.clawsaas.gateway.auth;

import java.util.Collection;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

public class AuthenticatedPrincipal extends User {
    private final String userId;
    private final String actorType;

    public AuthenticatedPrincipal(String userId, String username, String actorType, Collection<? extends GrantedAuthority> authorities) {
        super(username, "", authorities);
        this.userId = userId;
        this.actorType = actorType;
    }

    public String userId() {
        return userId;
    }

    public String actorType() {
        return actorType;
    }
}
