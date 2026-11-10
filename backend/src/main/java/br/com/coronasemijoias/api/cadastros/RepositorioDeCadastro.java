package br.com.coronasemijoias.api.cadastros;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

/** Base dos repositórios dos cadastros. A busca com página é escrita em cada um (campos diferentes). */
@NoRepositoryBean
interface RepositorioDeCadastro<E> extends JpaRepository<E, UUID> {
}
