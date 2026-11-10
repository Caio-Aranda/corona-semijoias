package br.com.coronasemijoias.api.cadastros;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface RepositorioDePracas extends RepositorioDeCadastro<Praca> {

    @Query("""
            select p from Praca p
            where p.deletedAt is null and lower(p.nome) like :texto
            order by lower(p.nome), p.id""")
    Page<Praca> buscar(@Param("texto") String texto, Pageable pagina);
}
