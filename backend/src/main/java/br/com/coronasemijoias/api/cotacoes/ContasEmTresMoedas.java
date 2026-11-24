package br.com.coronasemijoias.api.cotacoes;

import br.com.coronasemijoias.api.comum.dinheiro.ConversaoDeMoeda;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Dólar e Guarani das contas a pagar e a receber da dona (revisão de 23/11/2026, achado M3; ADR-0006, itens 7 e 10): cada
 * lançamento (a conta, cada pagamento) é convertido pela cotação do momento dele e, quando são vários, os convertidos são somados.
 * Só para mostrar: tudo continua gravado em Real.
 */
public final class ContasEmTresMoedas {

    /** Dólar (2 casas) e Guarani (sem casas); {@code null} nos dois quando falta a cotação de algum lançamento. */
    public record Moedas(BigDecimal dolar, BigDecimal guarani) {
        public static final Moedas ZERO = new Moedas(BigDecimal.ZERO.setScale(ConversaoDeMoeda.ESCALA_DOLAR),
                BigDecimal.ZERO.setScale(ConversaoDeMoeda.ESCALA_GUARANI));
        public static final Moedas SEM_COTACAO = new Moedas(null, null);
    }

    private ContasEmTresMoedas() {
    }

    /** {@code {id1,id2}} para {@code ?::uuid[]}. */
    public static String lista(Collection<UUID> ids) {
        return ids.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
    }

    /**
     * Roda a consulta, que devolve {@code conta_id, valor, usd_venda, pyg_venda} (a cotação do momento de cada lançamento), e soma
     * os convertidos por conta. Conta sem nenhuma linha fica fora do mapa (quem chama decide: zero para "já pago").
     */
    public static Map<UUID, Moedas> somar(JdbcTemplate jdbc, String sql, Object... args) {
        Map<UUID, BigDecimal[]> somas = new HashMap<>();
        Set<UUID> semCotacao = new HashSet<>();
        jdbc.query(sql, rs -> {
            UUID conta = rs.getObject(1, UUID.class);
            BigDecimal valor = rs.getBigDecimal(2);
            BigDecimal usd = rs.getBigDecimal(3);
            BigDecimal pyg = rs.getBigDecimal(4);
            BigDecimal[] s = somas.computeIfAbsent(conta, k -> new BigDecimal[] {Moedas.ZERO.dolar(), Moedas.ZERO.guarani()});
            if (usd == null || pyg == null) {
                semCotacao.add(conta);
                return;
            }
            s[0] = s[0].add(ConversaoDeMoeda.paraDolar(valor, usd));
            s[1] = s[1].add(ConversaoDeMoeda.paraGuarani(valor, pyg));
        }, args);
        Map<UUID, Moedas> r = new HashMap<>();
        somas.forEach((conta, s) -> r.put(conta, semCotacao.contains(conta) ? Moedas.SEM_COTACAO
                : new Moedas(ConversaoDeMoeda.paraCasas(s[0], ConversaoDeMoeda.ESCALA_DOLAR),
                        ConversaoDeMoeda.paraCasas(s[1], ConversaoDeMoeda.ESCALA_GUARANI))));
        return r;
    }
}
