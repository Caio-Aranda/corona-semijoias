package br.com.coronasemijoias.api.cadastros;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface RepositorioDeSemanas extends RepositorioDeCadastro<Semana> {

    /** Busca pelo número da semana (texto vazio traz todas), da mais nova para a mais antiga. */
    @Query("""
            select s from Semana s
            where s.deletedAt is null and cast(s.numero as string) like :texto
            order by s.numero desc""")
    Page<Semana> buscar(@Param("texto") String texto, Pageable pagina);
}
