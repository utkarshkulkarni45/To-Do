package com.todo.app.service;

import com.todo.app.Fixtures;
import com.todo.app.dto.LoginRequest;
import com.todo.app.dto.RegisterRequest;
import com.todo.app.entity.User;
import com.todo.app.enums.Role;
import com.todo.app.exception.ApiException;
import com.todo.app.exception.ErrorCodes;
import com.todo.app.mapper.ReferenceMapper;
import com.todo.app.repository.UserRepository;
import com.todo.app.security.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import static org.mockito.Mockito.mock;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private AuthenticationManager authenticationManager;
    @Mock
    private TokenService tokenService;

    private AuthService service;

    @BeforeEach
    void setUp() {
        service = new AuthService(userRepository, passwordEncoder, authenticationManager, tokenService,
                new ReferenceMapper());
    }

    @Test
    void registerNormalisesTheEmailHashesThePasswordAndReturnsAToken() {
        when(userRepository.existsByEmail("demo@todo.local")).thenReturn(false);
        when(passwordEncoder.encode("Summer2026!")).thenReturn("$2a$10$hash");
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(call -> {
            User user = call.getArgument(0);
            Fixtures.applyId(user, 55L);
            return user;
        });
        when(tokenService.issueAccessToken(any(), eq("Demo Person"))).thenReturn("signed.jwt.value");

        var response = service.register(new RegisterRequest("  Demo@ToDo.local ", " Demo Person ", "Summer2026!"));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("demo@todo.local");
        assertThat(saved.getValue().getFullName()).isEqualTo("Demo Person");
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("$2a$10$hash");
        assertThat(saved.getValue().getRole()).isEqualTo(Role.USER);
        assertThat(response.accessToken()).isEqualTo("signed.jwt.value");
        assertThat(response.user().email()).isEqualTo("demo@todo.local");
    }

    @Test
    void registerRejectsAnAddressThatIsAlreadyTaken() {
        when(userRepository.existsByEmail("taken@todo.local")).thenReturn(true);

        assertThatThrownBy(() -> service.register(new RegisterRequest("taken@todo.local", "Someone", "Summer2026!")))
                .isInstanceOfSatisfying(ApiException.class, failure -> {
                    assertThat(failure.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(failure.getCode()).isEqualTo(ErrorCodes.EMAIL_ALREADY_REGISTERED);
                });
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void registerTurnsAUniqueIndexRaceIntoTheSameConflict() {
        when(userRepository.existsByEmail("twin@todo.local")).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$hash");
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> service.register(new RegisterRequest("twin@todo.local", "Someone", "Summer2026!")))
                .isInstanceOfSatisfying(ApiException.class,
                        failure -> assertThat(failure.getCode()).isEqualTo(ErrorCodes.EMAIL_ALREADY_REGISTERED));
    }

    @Test
    void loginExchangesValidCredentialsForAToken() {
        User user = Fixtures.user(9L, "demo@todo.local", Role.USER);
        when(authenticationManager.authenticate(any())).thenReturn(mock(Authentication.class));
        when(userRepository.findByEmailAndDeletedFalse("demo@todo.local")).thenReturn(Optional.of(user));
        when(tokenService.issueAccessToken(any(), any())).thenReturn("token.value");

        var response = service.login(new LoginRequest(" Demo@todo.local ", "Summer2026!"));

        assertThat(response.accessToken()).isEqualTo("token.value");
        assertThat(response.user().id()).isEqualTo(9L);
    }

    @Test
    void loginHidesWhetherTheAccountOrThePasswordWasWrong() {
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("bad"));

        assertThatThrownBy(() -> service.login(new LoginRequest("demo@todo.local", "wrong")))
                .isInstanceOfSatisfying(ApiException.class, failure -> {
                    assertThat(failure.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(failure.getCode()).isEqualTo(ErrorCodes.INVALID_CREDENTIALS);
                    assertThat(failure.getMessage()).isEqualTo("Email or password is incorrect");
                });
    }

    @Test
    void loginRefusesADisabledAccountEvenWithTheRightPassword() {
        when(authenticationManager.authenticate(any())).thenReturn(mock(Authentication.class));
        when(userRepository.findByEmailAndDeletedFalse("gone@todo.local")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login(new LoginRequest("gone@todo.local", "Summer2026!")))
                .isInstanceOfSatisfying(ApiException.class,
                        failure -> assertThat(failure.getCode()).isEqualTo(ErrorCodes.INVALID_CREDENTIALS));
    }

    @Test
    void profileReportsAnUnknownAccountAsNotFound() {
        when(userRepository.findByIdAndDeletedFalse(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.profile(404L))
                .isInstanceOfSatisfying(ApiException.class,
                        failure -> assertThat(failure.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
