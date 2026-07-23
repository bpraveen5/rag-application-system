package com.ragapp.security;

import com.ragapp.entity.User;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Spring Security {@link UserDetails} adapter for {@link User}.
 */
@Getter
public class RagUserPrincipal implements UserDetails {

    private final UUID id;
    private final String username;
    private final String email;
    private final String password;
    private final String tenantId;
    private final boolean enabled;
    private final boolean accountNonLocked;
    private final Collection<? extends GrantedAuthority> authorities;

    private RagUserPrincipal(User user) {
        this.id            = user.getId();
        this.username      = user.getUsername();
        this.email         = user.getEmail();
        this.password      = user.getPasswordHash();
        this.tenantId      = user.getTenantId();
        this.enabled       = user.isEnabled();
        this.accountNonLocked = user.isAccountNonLocked();
        this.authorities   = buildAuthorities(user.getRoles());
    }

    public static RagUserPrincipal of(User user) {
        return new RagUserPrincipal(user);
    }

    private static Set<GrantedAuthority> buildAuthorities(Set<User.Role> roles) {
        return roles.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .collect(Collectors.toSet());
    }

    @Override public boolean isAccountNonExpired()  { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
}
