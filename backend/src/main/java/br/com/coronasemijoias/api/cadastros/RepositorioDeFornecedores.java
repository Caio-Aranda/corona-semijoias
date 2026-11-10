package br.com.coronasemijoias.api.cadastros;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface RepositorioDeFornecedores extends RepositorioDeCadastro<Fornecedor> {

    @Query("""
            select f from Fornecedor f
            where f.deletedAt is null and f.ativo in :situacoes
              and (lower(f.razaoSocial) like :texto or f.cnpj like :documento
                   or lower(coalesce(f.contatoNome, '')) like :texto)
            order by lower(f.razaoSocial), f.id""")
    Page<Fornecedor> buscar(@Param("texto") String texto, @Param("documento") String documento, @Param("situacoes") List<Boolean> situacoes, Pageable pagina);
}
