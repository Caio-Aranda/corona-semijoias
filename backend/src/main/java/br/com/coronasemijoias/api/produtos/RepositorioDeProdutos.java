package br.com.coronasemijoias.api.produtos;

import br.com.coronasemijoias.api.cadastros.RepositorioDeCadastro;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface RepositorioDeProdutos extends RepositorioDeCadastro<Produto> {

    /**
     * Busca pelo nome, pela referência ou por parte de qualquer código. Os filtros chegam como texto
     * (ou nulos) para o PostgreSQL saber o tipo mesmo quando o filtro não foi usado.
     */
    @Query(value = """
            select p.* from corona.produtos p
            where p.deleted_at is null
              and (cast(:categoria as uuid) is null or p.categoria_id = cast(:categoria as uuid))
              and (cast(:fornecedor as uuid) is null or p.fornecedor_id = cast(:fornecedor as uuid))
              and (cast(:ativo as boolean) is null or p.ativo = cast(:ativo as boolean))
              and (lower(p.nome) like :texto or lower(coalesce(p.referencia, '')) like :texto
                   or exists (select 1 from corona.produto_codigos c
                              where c.produto_id = p.id and c.deleted_at is null and lower(c.codigo) like :texto))
            order by lower(p.nome), p.id""",
            countQuery = """
            select count(*) from corona.produtos p
            where p.deleted_at is null
              and (cast(:categoria as uuid) is null or p.categoria_id = cast(:categoria as uuid))
              and (cast(:fornecedor as uuid) is null or p.fornecedor_id = cast(:fornecedor as uuid))
              and (cast(:ativo as boolean) is null or p.ativo = cast(:ativo as boolean))
              and (lower(p.nome) like :texto or lower(coalesce(p.referencia, '')) like :texto
                   or exists (select 1 from corona.produto_codigos c
                              where c.produto_id = p.id and c.deleted_at is null and lower(c.codigo) like :texto))""",
            nativeQuery = true)
    Page<Produto> buscar(@Param("texto") String texto, @Param("categoria") String categoria,
            @Param("fornecedor") String fornecedor, @Param("ativo") String ativo, Pageable pagina);
}
