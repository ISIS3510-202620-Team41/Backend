package com.group41.backend.user.service;

import com.group41.backend.auth.AuthDtos;
import com.group41.backend.auth.AuthService;
import com.group41.backend.user.domain.User;
import com.group41.backend.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Actualizacion parcial: un campo ausente (null) se deja como estaba.
     * Para borrar la descripcion el cliente manda cadena vacia, no null.
     */
    @Transactional
    public AuthDtos.UserResponse updateProfile(User user, AuthDtos.UpdateProfileRequest request) {
        if (request.name() != null && !request.name().isBlank()) {
            user.setName(request.name().trim());
        }
        if (request.bio() != null) {
            user.setBio(request.bio().isBlank() ? null : request.bio().trim());
        }

        userRepository.save(user);
        return AuthService.toUserResponse(user);
    }
}