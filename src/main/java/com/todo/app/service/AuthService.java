package com.todo.app.service;

import com.todo.app.dto.AuthResponse;
import com.todo.app.dto.LoginRequest;
import com.todo.app.dto.RegisterRequest;
import com.todo.app.dto.UserView;
import com.todo.app.entity.User;
import com.todo.app.enums.Role;
import com.todo.app.exception.ApiException;
import com.todo.app.exception.ErrorCodes;
import com.todo.app.mapper.ReferenceMapper;
import com.todo.app.repository.UserRepository;
import com.todo.app.security.AuthUser;
import com.todo.app.security.TokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final TokenService tokenService;
    private final ReferenceMapper mapper;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       AuthenticationManager authenticationManager, TokenService tokenService,
                       ReferenceMapper mapper) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.tokenService = tokenService;
        this.mapper = mapper;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = request.email().trim().toLowerCase();
        if (userRepository.existsByEmail(email)) {
            throw ApiException.conflict(ErrorCodes.EMAIL_ALREADY_REGISTERED, "That email is already registered");
        }
        User user = new User(email, request.fullName().trim(), passwordEncoder.encode(request.password()), Role.USER);
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException raced) {
            // Two registrations for the same address at the same time: the unique index decides.
            throw ApiException.conflict(ErrorCodes.EMAIL_ALREADY_REGISTERED, "That email is already registered");
        }
        log.info("Registered user id={} email={}", user.getId(), user.getEmail());
        return issueTokenFor(user);
    }

    public AuthResponse login(LoginRequest request) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.email().trim().toLowerCase(), request.password()));
        } catch (AuthenticationException invalid) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.INVALID_CREDENTIALS,
                    "Email or password is incorrect");
        }
        User user = userRepository.findByEmailAndDeletedFalse(request.email().trim().toLowerCase())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.INVALID_CREDENTIALS,
                        "Email or password is incorrect"));
        return issueTokenFor(user);
    }

    @Transactional(readOnly = true)
    public UserView profile(long userId) {
        return mapper.toView(userRepository.findByIdAndDeletedFalse(userId)
                .orElseThrow(() -> ApiException.notFound("Account", userId)));
    }

    private AuthResponse issueTokenFor(User user) {
        AuthUser principal = new AuthUser(user.getId(), user.getEmail(), user.getRole().name());
        String token = tokenService.issueAccessToken(principal, user.getFullName());
        return new AuthResponse(token, "Bearer", tokenService.expirationSeconds(), mapper.toView(user));
    }
}
