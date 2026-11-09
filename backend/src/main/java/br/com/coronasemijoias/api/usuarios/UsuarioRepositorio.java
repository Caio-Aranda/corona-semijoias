package br.com.coronasemijoias.api.usuarios;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UsuarioRepositorio extends JpaRepository<Usuario, UUID> {

    /** Busca pelo login já normalizado ({@link Logins#normalizar(String)}). */
    Optional<Usuario> findByLogin(String login);

    boolean existsByPerfil(String perfil);
}
