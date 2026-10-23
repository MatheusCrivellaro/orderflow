package com.matheus.orderflow.auth.dto;

import com.matheus.orderflow.user.Role;
import com.matheus.orderflow.user.User;

public record UserResponse(
        Long id,
        String name,
        String email,
        Role role
) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getName(), user.getEmail(), user.getRole());
    }
}
