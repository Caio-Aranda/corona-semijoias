package br.com.coronasemijoias.api.cadastros;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface RepositorioDeClientes extends RepositorioDeCadastro<Cliente> {

    @Query("""
            select c from Cliente c
            where c.deletedAt is null
              and (lower(c.nome) like :texto or c.cpf like :documento
                   or lower(coalesce(c.telefone, '')) like :texto or lower(coalesce(c.whatsapp, '')) like :texto)
            order by lower(c.nome), c.id""")
    Page<Cliente> buscar(@Param("texto") String texto, @Param("documento") String documento, Pageable pagina);
}
