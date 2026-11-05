package com.todo.app.security;

import com.todo.app.exception.ErrorBodyWriter;
import com.todo.app.exception.ErrorCodes;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final TokenService tokenService;
    private final ErrorBodyWriter errorBodyWriter;

    public JwtAuthenticationFilter(TokenService tokenService, ErrorBodyWriter errorBodyWriter) {
        this.tokenService = tokenService;
        this.errorBodyWriter = errorBodyWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }
        try {
            AuthUser principal = tokenService.readPrincipal(header.substring(BEARER_PREFIX.length()).trim());
            var authentication = new UsernamePasswordAuthenticationToken(principal, null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + principal.role())));
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            chain.doFilter(request, response);
        } catch (ExpiredJwtException expired) {
            SecurityContextHolder.clearContext();
            errorBodyWriter.write(response, HttpStatus.UNAUTHORIZED, ErrorCodes.TOKEN_EXPIRED, "Access token has expired");
        } catch (JwtException | IllegalArgumentException invalid) {
            SecurityContextHolder.clearContext();
            errorBodyWriter.write(response, HttpStatus.UNAUTHORIZED, ErrorCodes.TOKEN_INVALID, "Access token is not valid");
        }
    }
}
