package br.com.coronasemijoias.api.conciliacoes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * As conciliações da Fase 7 (tarefa 7.8; ADR-0012, item 17): C1 a C5 e C7 a C10 (a C6, de cheque, fica vaga). Só leitura, num
 * retrato só do banco, em Real, e nunca corrige nada. (Esqueleto do passo 1: as consultas chegam no passo 2.)
 */
@Service
public class ConciliacoesDaFase7 {

    public static final List<String> CHAVES = List.of("C1", "C2", "C3", "C4", "C5", "C7", "C8", "C9", "C10");

    public enum Unidade {
        REAIS, PECAS, REGISTROS, PERCENTUAL
    }

    /** Uma linha que não bate: o que é (tipo e id), o que devia ser e o que está gravado. */
    public record Diferenca(String tipo, UUID referencia, String descricao, BigDecimal esperado, BigDecimal encontrado,
            Unidade unidade) {
    }

    public record Conferencia(String chave, String titulo, String explicacao, long conferidos, List<Diferenca> divergencias) {
    }

    public record Resultado(Instant geradaEm, boolean tudoCerto, List<Conferencia> conferencias) {
    }

    private final JdbcTemplate jdbc;

    public ConciliacoesDaFase7(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Resultado conferir() {
        return new Resultado(Instant.now(), true, List.of());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Resultado conferir(String chave) {
        return new Resultado(Instant.now(), true, List.of());
    }
}
