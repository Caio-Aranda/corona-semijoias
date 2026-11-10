package br.com.coronasemijoias.api.vendedores;

import br.com.coronasemijoias.api.cadastros.RepositorioDeCadastro;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface RepositorioDeVendedores extends RepositorioDeCadastro<Vendedor> {

    /**
     * Busca pelo nome, pelo código (número exato), pelo CPF ou CNPJ e pelos telefones, só entre as
     * situações pedidas ({@code true} = ativo). Texto vazio traz todos.
     */
    @Query("""
            select v from Vendedor v
            where v.deletedAt is null and v.ativo in :situacoes
              and (lower(v.nome) like :texto or v.cpfCnpj like :documento or cast(v.codigo as string) = :codigo
                   or lower(coalesce(v.telefone1, '')) like :texto or lower(coalesce(v.telefone2, '')) like :texto
                   or lower(coalesce(v.whatsapp, '')) like :texto)
            order by lower(v.nome), v.id""")
    Page<Vendedor> buscar(@Param("texto") String texto, @Param("documento") String documento,
            @Param("codigo") String codigo, @Param("situacoes") List<Boolean> situacoes, Pageable pagina);
}
