package br.com.coronasemijoias.api.cadastros;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface RepositorioDeRestricoes extends RepositorioDeCadastro<Restricao> {

    @Query("""
            select r from Restricao r
            where r.deletedAt is null and r.ativo in :situacoes
              and (lower(r.codigo) like :texto or lower(r.descricao) like :texto)
            order by lower(r.codigo), r.id""")
    Page<Restricao> buscar(@Param("texto") String texto, @Param("situacoes") List<Boolean> situacoes, Pageable pagina);
}
