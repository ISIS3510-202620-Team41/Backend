package com.group41.backend.user;

import com.group41.backend.auth.AuthDtos;
import com.group41.backend.auth.AuthService;
import com.group41.backend.storage.AvatarStorageService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;
    private final AvatarStorageService avatarStorageService;

    public UserController(UserService userService, AvatarStorageService avatarStorageService) {
        this.userService = userService;
        this.avatarStorageService = avatarStorageService;
    }

    /**
     * Perfil del usuario autenticado.
     *
     * @AuthenticationPrincipal entrega el User que dejo JwtAuthFilter en el
     * contexto, asi que nunca hay que confiar en un id enviado por el cliente.
     */
    @GetMapping("/me")
    public ResponseEntity<AuthDtos.UserResponse> me(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(AuthService.toUserResponse(user));
    }

    /** Edita nombre y descripcion. Alimenta el bottom sheet "Editar perfil". */
    @PatchMapping("/me")
    public ResponseEntity<AuthDtos.UserResponse> updateProfile(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody AuthDtos.UpdateProfileRequest request) {
        return ResponseEntity.ok(userService.updateProfile(user, request));
    }

    /** Sube o reemplaza la foto de perfil. Feature #11. */
    @PostMapping("/me/avatar")
    public ResponseEntity<AuthDtos.UserResponse> uploadAvatar(
            @AuthenticationPrincipal User user,
            @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(avatarStorageService.storeAvatar(user, file));
    }

    /** Quita la foto de perfil y borra el archivo del disco. */
    @DeleteMapping("/me/avatar")
    public ResponseEntity<AuthDtos.UserResponse> deleteAvatar(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(avatarStorageService.removeAvatar(user));
    }
}
