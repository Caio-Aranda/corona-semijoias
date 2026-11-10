package br.com.coronasemijoias.api.relatorios;

import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.relatorios.RelatorioDeCadastro.Total;
import java.math.BigDecimal;
import br.com.coronasemijoias.api.comum.dinheiro.Dinheiro;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Uma única fonte de cálculo para os relatórios de cadastro (§13, item 2): cada relatório só descreve o
 * FROM, as condições e o rodapé; as linhas, a contagem, os totais e o detalhamento de cada total saem
 * das MESMAS condições, então o rodapé e o detalhamento nunca discordam.
 */
@Component
class MotorDeRelatorio {

    static final int TAMANHO_PADRAO = 20;
    static final int TAMANHO_MAXIMO = 100;
    static final String TODOS = "todos";

    /** Um número do rodapé: as linhas que o formam (predicado) e, se tiver, o valor que se soma. */
    record DefinicaoDeTotal(String chave, String rotulo, String predicado, String valor, String pecas) {

        static DefinicaoDeTotal contagem(String chave, String rotulo, String predicado) {
            return new DefinicaoDeTotal(chave, rotulo, predicado, null, null);
        }

        static DefinicaoDeTotal soma(String chave, String rotulo, String valor) {
            return new DefinicaoDeTotal(chave, rotulo, null, valor, null);
        }

        /** Linhas que formam o total, com a soma de um valor em Real. */
        static DefinicaoDeTotal somaDe(String chave, String rotulo, String predicado, String valor) {
            return new DefinicaoDeTotal(chave, rotulo, predicado, valor, null);
        }

        /** Linhas que formam o total, com a soma de peças (inteiro) e, se tiver, de um valor em Real. */
        static DefinicaoDeTotal pecas(String chave, String rotulo, String predicado, String pecas, String valor) {
            return new DefinicaoDeTotal(chave, rotulo, predicado, valor, pecas);
        }
    }

    /** Montagem da consulta de um relatório. */
    static final class Consulta {
        final String colunas;
        final String de;
        final String ordem;
        final List<DefinicaoDeTotal> totais;
        final List<String> condicoes = new ArrayList<>();
        final MapSqlParameterSource parametros = new MapSqlParameterSource();

        Consulta(String colunas, String de, String ordem, List<DefinicaoDeTotal> totais) {
            this.colunas = colunas;
            this.de = de;
            this.ordem = ordem;
            this.totais = totais;
        }

        Consulta onde(String condicao) {
            condicoes.add(condicao);
            return this;
        }

        Consulta onde(String condicao, String nome, Object valor) {
            condicoes.add(condicao);
            parametros.addValue(nome, valor);
            return this;
        }

        /** Período em datas (UTC), inclusive nas duas pontas, sobre a coluna dada. */
        Consulta periodo(String coluna, LocalDate de, LocalDate ate) {
            if (de != null) {
                onde(coluna + " >= :periodoDe", "periodoDe", OffsetDateTime.of(de.atStartOfDay(), ZoneOffset.UTC));
            }
            if (ate != null) {
                onde(coluna + " < :periodoAte", "periodoAte",
                        OffsetDateTime.of(ate.plusDays(1).atStartOfDay(), ZoneOffset.UTC));
            }
            return this;
        }

        /** Busca no texto de uma ou mais colunas (sem diferenciar maiúsculas), se a pessoa digitou algo. */
        Consulta busca(String busca, String... expressoes) {
            String limpo = limparBusca(busca);
            if (limpo.isEmpty()) {
                return this;
            }
            parametros.addValue("texto", "%" + limpo + "%");
            List<String> partes = new ArrayList<>();
            for (String e : expressoes) {
                partes.add("lower(coalesce(" + e + ", '')) LIKE :texto");
            }
            return onde("(" + String.join(" OR ", partes) + ")");
        }

        String clausulaWhere() {
            return condicoes.isEmpty() ? "" : " WHERE " + String.join(" AND ", condicoes);
        }
    }

    private final NamedParameterJdbcTemplate jdbc;

    MotorDeRelatorio(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    static String limparBusca(String busca) {
        return busca == null ? "" : busca.strip().replaceAll("[%_\\\\]", "").toLowerCase(Locale.ROOT);
    }

    static LocalDate conferirPeriodo(LocalDate de, LocalDate ate) {
        if (de != null && ate != null && ate.isBefore(de)) {
            throw Validacoes.recusar("ate", "A data final não pode ser antes da data inicial.");
        }
        return de;
    }

    static int pagina(Integer pagina) {
        int p = pagina == null ? 0 : pagina;
        if (p < 0) {
            throw Validacoes.recusar("pagina", "A página não pode ser negativa.");
        }
        return p;
    }

    static int tamanho(Integer tamanho) {
        int t = tamanho == null ? TAMANHO_PADRAO : tamanho;
        if (t < 1 || t > TAMANHO_MAXIMO) {
            throw Validacoes.recusar("tamanho", "Escolha de 1 a " + TAMANHO_MAXIMO + " itens por página.");
        }
        return t;
    }

    static UUID idOpcional(String valor, String campo) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(valor.strip());
        } catch (IllegalArgumentException e) {
            throw Validacoes.recusar(campo, "Escolha uma das opções da lista.");
        }
    }

    <L> RelatorioDeCadastro<L> executar(Consulta c, String recorte, Integer paginaPedida, Integer tamanhoPedido,
            RowMapper<L> linha) {
        int p = pagina(paginaPedida);
        int t = tamanho(tamanhoPedido);
        String pedido = recorte == null || recorte.isBlank() ? null : recorte.strip();
        DefinicaoDeTotal escolhido = null;
        if (pedido != null) {
            escolhido = c.totais.stream().filter(d -> d.chave().equals(pedido)).findFirst()
                    .orElseThrow(() -> Validacoes.recusar("recorte", "Escolha um dos totais do relatório."));
        }
        String where = c.clausulaWhere();
        String comRecorte = where;
        if (escolhido != null && escolhido.predicado() != null) {
            comRecorte = (where.isEmpty() ? " WHERE " : where + " AND ") + "(" + escolhido.predicado() + ")";
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM " + c.de + comRecorte, c.parametros, Long.class);
        MapSqlParameterSource comPagina = new MapSqlParameterSource(c.parametros.getValues())
                .addValue("limite", t).addValue("inicio", (long) p * t);
        List<L> itens = jdbc.query("SELECT " + c.colunas + " FROM " + c.de + comRecorte + " ORDER BY " + c.ordem
                + " LIMIT :limite OFFSET :inicio", comPagina, linha);
        List<Total> totais = new ArrayList<>();
        for (DefinicaoDeTotal d : c.totais) {
            String filtro = where;
            if (d.predicado() != null) {
                filtro = (where.isEmpty() ? " WHERE " : where + " AND ") + "(" + d.predicado() + ")";
            }
            String soma = d.valor() == null ? "0" : "coalesce(sum(" + d.valor() + "), 0)";
            String pecas = d.pecas() == null ? "0" : "coalesce(sum(" + d.pecas() + "), 0)";
            Total feito = jdbc.queryForObject("SELECT count(*) AS quantidade, " + soma + " AS valor, " + pecas
                    + " AS pecas FROM " + c.de + filtro,
                    c.parametros, (rs, n) -> new Total(d.chave(), d.rotulo(), rs.getLong("quantidade"),
                            d.valor() == null ? null : Dinheiro.arredondar(rs.getBigDecimal("valor")),
                            d.pecas() == null ? null : rs.getLong("pecas")));
            totais.add(feito);
        }
        return new RelatorioDeCadastro<>(itens, total == null ? 0 : total, p, t, totais, pedido);
    }

    static BigDecimal reais(java.sql.ResultSet rs, String coluna) throws java.sql.SQLException {
        BigDecimal v = rs.getBigDecimal(coluna);
        return v == null ? null : v.setScale(2);
    }
}
