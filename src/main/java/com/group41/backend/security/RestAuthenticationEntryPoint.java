package com.group41.backend.security;

import com.group41.backend.common.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * Sin esto Spring Security responde 403 cuando no hay sesion, que para una API
 * REST es incorrecto.
 *
 * La distincion importa en el cliente Android:
 *   401 -> no hay token o expiro  -> mandar al usuario al login
 *   403 -> hay sesion valida pero no tiene permiso -> mostrar un mensaje
 *
 * Si los dos casos devolvieran 403, la app no sabria cuando cerrar la sesion.
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        ApiError error = ApiError.of(
                HttpStatus.UNAUTHORIZED.value(),
                "No autenticado");

        objectMapper.writeValue(response.getOutputStream(), error);
    }
}
