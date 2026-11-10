package br.com.coronasemijoias.api.vendedores;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

/** O que todas as abas de uma linha por vendedor têm em comum. */
@NoRepositoryBean
interface RepositorioDaSecao<E extends SecaoDoVendedor> extends JpaRepository<E, UUID> {

    Optional<E> findByVendedorId(UUID vendedorId);
}
