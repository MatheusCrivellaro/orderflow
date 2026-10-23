package com.matheus.orderflow.auth;

import com.matheus.orderflow.auth.dto.LoginRequest;
import com.matheus.orderflow.auth.dto.RegisterRequest;
import com.matheus.orderflow.auth.dto.TokenResponse;
import com.matheus.orderflow.auth.dto.UserResponse;
import com.matheus.orderflow.common.exception.ConflictException;
import com.matheus.orderflow.user.Role;
import com.matheus.orderflow.user.User;
import com.matheus.orderflow.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private TokenService tokenService;

    @InjectMocks
    private AuthService authService;

    @Test
    @DisplayName("Cadastra usuário com senha criptografada, e-mail minúsculo e perfil USER")
    void shouldRegisterUser() {
        when(userRepository.existsByEmail("ana@email.com")).thenReturn(false);
        when(passwordEncoder.encode("123456")).thenReturn("hash");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse response = authService.register(new RegisterRequest("Ana", "Ana@Email.com", "123456"));

        assertThat(response.email()).isEqualTo("ana@email.com");
        assertThat(response.role()).isEqualTo(Role.USER);
    }

    @Test
    @DisplayName("Não permite cadastrar e-mail repetido")
    void shouldRejectDuplicatedEmail() {
        when(userRepository.existsByEmail("ana@email.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(new RegisterRequest("Ana", "ana@email.com", "123456")))
                .isInstanceOf(ConflictException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("Login com senha correta retorna token")
    void shouldLogin() {
        User user = new User("Ana", "ana@email.com", "hash", Role.USER);
        TokenResponse token = new TokenResponse("jwt", "Bearer", 3600);
        when(userRepository.findByEmail("ana@email.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("123456", "hash")).thenReturn(true);
        when(tokenService.generate(user)).thenReturn(token);

        assertThat(authService.login(new LoginRequest("ana@email.com", "123456"))).isEqualTo(token);
    }

    @Test
    @DisplayName("Login com senha errada lança BadCredentials")
    void shouldRejectWrongPassword() {
        User user = new User("Ana", "ana@email.com", "hash", Role.USER);
        when(userRepository.findByEmail("ana@email.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("errada", "hash")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest("ana@email.com", "errada")))
                .isInstanceOf(BadCredentialsException.class);
        verify(tokenService, never()).generate(any());
    }
}
