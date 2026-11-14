package br.com.coronasemijoias.api.acertos;

import br.com.coronasemijoias.api.comum.dinheiro.ConversaoDeMoeda;
import br.com.coronasemijoias.api.comum.dinheiro.Dinheiro;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A comissão por meta do acerto (tarefa 7.5; ADR-0012, item 5), sem banco e sem relógio: quem chama lê as vendas, os estornos
 * e a configuração; aqui só entra a conta. Todo arredondamento é de {@link Dinheiro} e {@link ConversaoDeMoeda}.
 *
 * <ul>
 *   <li><strong>Soma da meta</strong>, em Guarani inteiro: cada venda do acerto pelo TOTAL dela (já depois do desconto), convertido
 *       pela cotação dela e arredondado sozinho, menos cada estorno ou devolução <em>cuja venda também está neste acerto</em>
 *       (convertido pela cotação da venda). A soma é de inteiros já convertidos, nunca a conversão do total. Estorno e devolução de
 *       venda de acerto fechado não entram na soma.</li>
 *   <li><strong>Percentual</strong>: soma maior ou igual à meta, o "na meta"; abaixo, o "abaixo da meta". Vale para todas as
 *       vendas do acerto.</li>
 *   <li><strong>Linha COMISSAO</strong> por venda: {@code Dinheiro.aplicarPorcentagem(total, percentual)}, uma vez por venda.</li>
 *   <li><strong>Linha ESTORNO</strong> por estorno ou devolução: proporcional, pelo acumulado
 *       ({@code Dinheiro.estornosProporcionais}), com o percentual da linha original (40% continua 40% mesmo que este acerto
 *       fique em 30%). Base zero não gera linha.</li>
 * </ul>
 */
public final class CalculoDaComissao {

    /** Uma venda do acerto: {@code total} depois do desconto; {@code taxaGuarani} é a taxa de venda da cotação da venda. */
    public record Venda(UUID id, UUID eventoId, BigDecimal total, BigDecimal taxaGuarani) {
    }

    /** Um estorno de venda ou uma devolução do acerto, na ordem em que aconteceram; {@code valor} positivo. */
    public record Estorno(UUID eventoId, UUID vendaId, BigDecimal valor) {
    }

    /**
     * A comissão original de uma venda de acerto já fechado: a base e o valor da linha COMISSAO, o percentual dela e as
     * devoluções que já a estornaram (as bases, positivas, na ordem).
     */
    public record Original(BigDecimal base, BigDecimal valor, BigDecimal percentual, List<BigDecimal> devolvidoAntes) {
    }

    public enum TipoDaLinha { COMISSAO, ESTORNO }

    /** Uma linha de {@code comissoes}: {@code valor} negativo no ESTORNO; {@code base} é o total da venda ou o valor devolvido. */
    public record Linha(UUID vendaId, UUID eventoId, TipoDaLinha tipo, BigDecimal base, BigDecimal percentual, BigDecimal valor) {
    }

    public record Resultado(long vendidoParaMetaGuarani, boolean naMeta, BigDecimal percentual, List<Linha> linhas,
            BigDecimal comissao) {
    }

    private CalculoDaComissao() {
    }

    /**
     * @param estornos          na ordem em que aconteceram (por venda, a ordem das devoluções importa)
     * @param deAcertosFechados a comissão original das vendas que já estão em acerto fechado e têm estorno neste
     */
    public static Resultado calcular(long metaGuarani, BigDecimal percentualAbaixoDaMeta, BigDecimal percentualNaMeta,
            List<Venda> vendas, List<Estorno> estornos, Map<UUID, Original> deAcertosFechados) {
        Map<UUID, Venda> vendasPorId = new LinkedHashMap<>();
        for (Venda v : vendas) {
            vendasPorId.put(v.id(), v);
        }
        long soma = 0;
        for (Venda v : vendas) {
            soma += ConversaoDeMoeda.paraGuarani(v.total(), v.taxaGuarani()).longValueExact();
        }
        for (Estorno e : estornos) {
            Venda v = vendasPorId.get(e.vendaId());
            if (v != null) {
                soma -= ConversaoDeMoeda.paraGuarani(e.valor(), v.taxaGuarani()).longValueExact();
            }
        }
        boolean naMeta = soma >= metaGuarani;
        BigDecimal percentual = naMeta ? percentualNaMeta : percentualAbaixoDaMeta;

        List<Linha> linhas = new ArrayList<>();
        Map<UUID, Original> originais = new HashMap<>(deAcertosFechados);
        for (Venda v : vendas) {
            BigDecimal valor = Dinheiro.aplicarPorcentagem(v.total(), percentual);
            linhas.add(new Linha(v.id(), v.eventoId(), TipoDaLinha.COMISSAO, v.total(), percentual, valor));
            originais.put(v.id(), new Original(v.total(), valor, percentual, List.of()));
        }

        Map<UUID, List<BigDecimal>> devolvidas = new HashMap<>();
        for (Estorno e : estornos) {
            Original o = originais.get(e.vendaId());
            if (o == null) {
                throw new IllegalStateException("A venda " + e.vendaId() + " tem estorno mas não tem comissão original.");
            }
            if (o.base().signum() == 0 || e.valor().signum() == 0) {
                continue;
            }
            List<BigDecimal> bases = devolvidas.computeIfAbsent(e.vendaId(), k -> new ArrayList<>(o.devolvidoAntes()));
            BigDecimal jaDevolvido = Dinheiro.somar(bases);
            if (jaDevolvido.add(e.valor()).compareTo(o.base()) > 0) {
                throw new IllegalStateException("As devoluções da venda " + e.vendaId() + " passam do total dela.");
            }
            bases.add(e.valor());
            List<BigDecimal> estornosDaVenda = Dinheiro.estornosProporcionais(o.valor(), o.base(), bases);
            BigDecimal estorno = estornosDaVenda.get(estornosDaVenda.size() - 1);
            if (estorno.signum() == 0) {
                continue;
            }
            linhas.add(new Linha(e.vendaId(), e.eventoId(), TipoDaLinha.ESTORNO, e.valor(), o.percentual(), estorno.negate()));
        }
        BigDecimal comissao = Dinheiro.somar(linhas.stream().map(Linha::valor).toList());
        return new Resultado(soma, naMeta, percentual, List.copyOf(linhas), comissao);
    }
}
