package br.com.coronasemijoias.api.cotacoes;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Qual cotação vale (ADR-0006, itens 7 e 15). Só leitura; a conversão em si é da
 * {@link br.com.coronasemijoias.api.comum.dinheiro.ConversaoDeMoeda}.
 *
 * <p>Vale a cotação de maior {@code vigente_desde} menor ou igual ao <strong>momento</strong> do lançamento (desempate:
 * maior id). A do Banco Central vale desde 00:00 do dia em Brasília, então, só com ela, o resultado é o de sempre
 * (sábado, domingo e feriado caem no último dia útil). A que a dona digita vale desde a hora em que foi gravada.
 * O acerto no fechamento e o que não é lançamento usam a vigente <em>agora</em>. Vazio só antes da primeira busca.
 */
@Component
public class CotacaoVigente {

    /** Horário de Brasília (UTC-3, sem horário de verão hoje). */
    public static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");

    /**
     * Em SQL, a regra é sempre esta (a única forma permitida fora desta classe): com a tabela como {@code c},
     * {@code WHERE c.vigente_desde <= momento ORDER BY c.vigente_desde DESC, c.id DESC LIMIT 1}.
     */
    public static final String SQL_ORDEM_DA_VIGENTE = "c.vigente_desde DESC, c.id DESC";

    private final RepositorioDeCotacoes repositorio;

    public CotacaoVigente(RepositorioDeCotacoes repositorio) {
        this.repositorio = repositorio;
    }

    /** A cotação de um lançamento feito neste instante (UTC); para "agora", passe o instante do relógio. */
    public Optional<Cotacao> vigenteEm(Instant momento) {
        return repositorio.vigenteEm(momento);
    }

    /**
     * O id da cotação vigente no momento, para quem só precisa gravar o {@code cotacao_id} do lançamento; {@code null}
     * se ainda não existe nenhuma (sistema novo, antes da primeira busca).
     */
    public static UUID idVigenteEm(JdbcTemplate jdbc, Instant momento) {
        return jdbc.query("SELECT c.id FROM corona.cotacoes c WHERE c.vigente_desde <= ? ORDER BY "
                        + SQL_ORDEM_DA_VIGENTE + " LIMIT 1",
                (rs, n) -> rs.getObject(1, UUID.class), momento.atOffset(ZoneOffset.UTC)).stream().findFirst().orElse(null);
    }

    /**
     * O SQL padrão para ligar a cotação vigente num momento: {@code LEFT JOIN LATERAL} com o apelido {@code ct} e as colunas
     * pedidas (do apelido {@code c}). {@code momentoSql} é uma expressão {@code timestamptz} da consulta (por exemplo
     * {@code p.pago_em}).
     */
    public static String joinDoMomento(String momentoSql, String colunas) {
        return "LEFT JOIN LATERAL (SELECT " + colunas + " FROM corona.cotacoes c WHERE c.vigente_desde <= " + momentoSql
                + " ORDER BY " + SQL_ORDEM_DA_VIGENTE + " LIMIT 1) ct ON true";
    }

    /**
     * O último instante de um dia (expressão {@code date} da consulta) em Brasília: o equivalente exato do antigo "cotação
     * até o dia". Para quem só tem a data do lançamento (vencimento, data do caixa manual).
     */
    public static String fimDoDiaSql(String dataSql) {
        return "((" + dataSql + " + 1)::timestamp AT TIME ZONE 'America/Sao_Paulo' - interval '1 microsecond')";
    }

    /** O dia (data de calendário) de um instante em Brasília. */
    public static LocalDate diaEmBrasilia(Instant momento) {
        return momento.atZone(BRASILIA).toLocalDate();
    }

    /** O último instante de um dia em Brasília (o "até o fim do dia" das datas de relatório). */
    public static Instant fimDoDia(LocalDate dia) {
        return dia.plusDays(1).atStartOfDay(BRASILIA).toInstant().minusNanos(1000);
    }
}
