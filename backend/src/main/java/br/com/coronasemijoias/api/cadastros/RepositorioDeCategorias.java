package br.com.coronasemijoias.api.cadastros;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface RepositorioDeCategorias extends RepositorioDeCadastro<Categoria> {

    @Query("""
            select c from Categoria c
            where c.deletedAt is null and c.ativo in :situacoes and lower(c.nome) like :texto
            order by lower(c.nome), c.id""")
    Page<Categoria> buscar(@Param("texto") String texto, @Param("situacoes") List<Boolean> situacoes, Pageable pagina);
}
