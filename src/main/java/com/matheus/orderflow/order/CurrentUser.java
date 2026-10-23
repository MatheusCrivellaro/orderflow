package com.matheus.orderflow.order;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

/**
 * Dados do usuário autenticado extraídos do token JWT.
 */
public record CurrentUser(Long id, boolean admin) {

    public static CurrentUser from(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        boolean admin = roles != null && roles.contains("ADMIN");
        return new CurrentUser(Long.valueOf(jwt.getSubject()), admin);
    }
}
