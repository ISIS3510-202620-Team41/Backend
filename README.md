# Backend

API REST para la app Android del equipo 41. Spring Boot 4 + Java 21.

Cubre dos features:

- **#15 Login Authentication** — registro, login, access + refresh tokens, logout y límite de intentos
- **#11 Profile Picture** — subida de foto con reescalado, limpieza de EXIF, reemplazo, borrado y edición de perfil

---

## Cómo correrlo

```bash
./mvnw spring-boot:run
```

Queda en `http://localhost:8080`. No hay que instalar base de datos: usa H2 en un archivo local (`./data/appdb.mv.db`), que se crea solo la primera vez.

```bash
./mvnw test
```

18 tests que cubren los dos features de punta a punta.

### Variables de entorno

Copia `.env.example` a `.env`. Las dos que importan:

| Variable | Para qué | Default (solo desarrollo) |
|---|---|---|
| `JWT_SECRET` | Firma los tokens. **Mínimo 32 caracteres** | uno de desarrollo, inseguro |
| `PUBLIC_BASE_URL` | Prefijo de las URLs de las imágenes | `http://localhost:8080` |

El servidor **no arranca** si `JWT_SECRET` tiene menos de 32 caracteres. Es a propósito: es preferible fallar al arrancar que emitir tokens débiles sin que nadie se entere.

---

## Contrato de la API

Todo bajo `/api`. JSON, salvo la subida de imagen que es `multipart/form-data`.

### Autenticación

| Método | Ruta | Auth | Qué hace |
|---|---|---|---|
| `POST` | `/api/auth/register` | — | Crea la cuenta y devuelve el par de tokens |
| `POST` | `/api/auth/login` | — | Devuelve el par de tokens |
| `POST` | `/api/auth/refresh` | — | Canjea el refresh por un par nuevo |
| `POST` | `/api/auth/logout` | — | Revoca el refresh token |

### Usuario

| Método | Ruta | Auth | Qué hace |
|---|---|---|---|
| `GET` | `/api/users/me` | Bearer | Perfil del usuario |
| `PATCH` | `/api/users/me` | Bearer | Edita `name` y/o `bio` |
| `POST` | `/api/users/me/avatar` | Bearer | Sube o reemplaza la foto |
| `DELETE` | `/api/users/me/avatar` | Bearer | Quita la foto |

### Cuerpos

`POST /api/auth/register`

```json
{ "email": "sebas@test.com", "password": "micontrasena123", "name": "Sebastian" }
```

`POST /api/auth/login`

```json
{ "email": "sebas@test.com", "password": "micontrasena123" }
```

`POST /api/auth/refresh` y `POST /api/auth/logout`

```json
{ "refreshToken": "..." }
```

Las tres primeras responden lo mismo:

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "refreshToken": "x9Kd2...",
  "expiresInSeconds": 900,
  "user": {
    "id": "uuid",
    "email": "sebas@test.com",
    "name": "Sebastian",
    "bio": null,
    "avatarUrl": null
  }
}
```

`PATCH /api/users/me` — actualización parcial: lo que no mandes se queda igual. Para borrar la bio manda `""`, no `null`.

```json
{ "name": "Sebastian", "bio": "Estudiante de Diseño" }
```

`POST /api/users/me/avatar` — `multipart/form-data` con un campo llamado **`file`**. Devuelve el `user` actualizado.

### Errores

Siempre la misma forma, para que el cliente la parsee una sola vez:

```json
{ "timestamp": "...", "status": 401, "message": "Credenciales invalidas", "fields": null }
```

`fields` solo viene en los `400` de validación, con el detalle por campo.

| Código | Cuándo |
|---|---|
| `400` | Validación fallida, o el archivo no es una imagen |
| `401` | Sin token, token inválido/expirado, o credenciales incorrectas |
| `409` | El correo ya está registrado |
| `413` | La imagen pesa más de 5 MB |
| `415` | Formato de imagen no permitido |
| `429` | Demasiados intentos de login |

---

## Notas para el cliente Android

**La URL base no es `localhost`.** Desde el emulador, `localhost` es el emulador mismo:

| Dónde corre la app | URL base |
|---|---|
| Emulador de Android Studio | `http://10.0.2.2:8080` |
| Teléfono físico en la misma WiFi | `http://<ip-de-tu-pc>:8080` |

**Android bloquea HTTP sin cifrar** desde API 28. Para desarrollo hay que permitir cleartext solo para ese host con un `network_security_config.xml`.

**Guarda los dos tokens.** El access dura 15 minutos, el refresh 30 días.

**Maneja el 401 con reintento.** Cuando una petición devuelva `401`, el interceptor debe llamar a `/api/auth/refresh`, guardar el par nuevo y reintentar. Solo si el refresh también falla se manda al usuario al login.

**El refresh token rota.** Cada llamada a `/refresh` invalida el anterior y entrega uno nuevo: hay que guardar siempre el último. Si se reusa uno viejo, responde `401`.

**Distingue 401 de 403.** `401` es "no hay sesión o expiró" y dispara el refresh. `403` es "hay sesión pero no tienes permiso" y no se debe reintentar.

---

## Decisiones de seguridad

**Contraseñas con BCrypt, factor 12.** Es lento a propósito (~250 ms). No se nota al iniciar sesión pero encarece muchísimo un ataque por fuerza bruta si la base de datos se filtra. Cada usuario lleva su propio salt. El hasheo ocurre **en el servidor**: si se hiciera en el cliente, el hash pasaría a ser la contraseña.

**Mismo error para correo inexistente y contraseña incorrecta.** Si se distinguieran, cualquiera podría averiguar qué correos están registrados probándolos uno por uno. Hay un test que lo verifica.

**Access token corto, refresh revocable.** El access dura 15 minutos y es un JWT autocontenido: rápido de validar pero imposible de anular antes de que expire. El refresh dura 30 días y vive en base de datos, así que se puede revocar — es lo que hace `logout`.

**El refresh rota en cada uso.** Si alguien roba un refresh token, deja de servir en cuanto el usuario legítimo refresque.

**Del refresh token solo se guarda el hash** (SHA-256). Si la tabla se filtra, los tokens no son utilizables. Se usa SHA-256 y no BCrypt porque el token ya es de 256 bits aleatorios: no hace falta un hash lento, y uno determinista permite buscarlo por índice.

**Límite de 5 intentos de login** por combinación correo + IP en 15 minutos. Se usan los dos juntos a propósito: solo por IP castigaría a todo un campus tras un NAT compartido, y solo por correo permitiría bloquear la cuenta de otra persona a punta de intentos fallidos.

**Las imágenes se reescriben, no se guardan tal cual.** El servidor las decodifica, las reduce a 512 px por el lado mayor y las vuelve a codificar como JPEG. Eso descarta todos los metadatos del original, **incluido el EXIF con las coordenadas GPS**. Guardar el archivo original sería publicar dónde vive el usuario.

**El nombre del archivo lo genera el servidor** (un UUID). Usar el que manda el cliente permitiría un `../../` para escribir fuera de la carpeta. Al borrar se comprueba además que la ruta resuelta siga dentro de la carpeta de subidas.

**Se valida que el archivo sea realmente una imagen**, no solo que el `Content-Type` lo diga.

---

## Limitaciones conocidas

**El contador de intentos de login vive en memoria.** Se pierde al reiniciar y no se comparte entre instancias. Para una sola instancia alcanza; si escalan a varias, hay que moverlo a Redis.

**No hay recuperación de contraseña ni verificación de correo.** Las dos necesitan enviar emails, lo que exige decidir un proveedor (SendGrid, Mailgun, SMTP propio) y tener credenciales. Queda pendiente de esa decisión.

**H2 y disco local.** Para producción hay que cambiar las cuatro líneas de `spring.datasource`, pasar `spring.jpa.hibernate.ddl-auto` a `validate` y gestionar el esquema con Flyway. Las imágenes irían a S3, Cloudinary o Firebase Storage: solo habría que reemplazar los métodos de escritura y borrado de `AvatarStorageService`.

**Los refresh tokens expirados no se limpian solos.** El repositorio tiene `deleteAllByExpiresAtBefore` listo, pero falta una tarea programada que lo llame.
