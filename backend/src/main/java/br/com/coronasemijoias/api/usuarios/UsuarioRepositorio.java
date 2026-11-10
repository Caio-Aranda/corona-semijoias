package br.com.coronasemijoias.api.usuarios;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UsuarioRepositorio extends JpaRepository<Usuario, UUID> {

    /** Busca pelo login já normalizado ({@link Logins#normalizar(String)}). */
    Optional<Usuario> findByLogin(String login);

    boolean existsByPerfil(String perfil);

    /**
     * Busca pelo nome e pelo login, só entre as situações pedidas ({@code true} = ativo). Perfil vazio
     * traz todos. Texto vazio (padrão "%%") traz todos.
     */
    @Query("""
            select u from Usuario u
            where u.ativo in :situacoes and (:perfil = '' or u.perfil = :perfil)
              and (lower(u.nome) like :texto or u.login like :texto)
            order by lower(u.nome), u.id""")
    Page<Usuario> buscar(@Param("texto") String texto, @Param("perfil") String perfil,
            @Param("situacoes") List<Boolean> situacoes, Pageable pagina);
}
