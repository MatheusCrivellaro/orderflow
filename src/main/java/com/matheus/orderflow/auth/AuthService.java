package com.matheus.orderflow.auth;

import com.matheus.orderflow.auth.dto.LoginRequest;
import com.matheus.orderflow.auth.dto.RegisterRequest;
import com.matheus.orderflow.auth.dto.TokenResponse;
import com.matheus.orderflow.auth.dto.UserResponse;
import com.matheus.orderflow.common.exception.ConflictException;
import com.matheus.orderflow.user.Role;
import com.matheus.orderflow.user.User;
import com.matheus.orderflow.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;

    @Transactional
    public UserResponse register(RegisterRequest request) {
        String email = request.email().toLowerCase();
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException("E-mail já cadastrado");
        }
        User user = new User(request.name(), email, passwordEncoder.encode(request.password()), Role.USER);
        return UserResponse.from(userRepository.save(user));
    }

    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email().toLowerCase())
                .filter(found -> passwordEncoder.matches(request.password(), found.getPassword()))
                .orElseThrow(() -> new BadCredentialsException("E-mail ou senha inválidos"));
        return tokenService.generate(user);
    }
}
