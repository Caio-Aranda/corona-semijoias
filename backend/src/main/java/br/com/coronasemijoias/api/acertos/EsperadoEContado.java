package br.com.coronasemijoias.api.acertos;

import br.com.coronasemijoias.api.comum.dinheiro.Dinheiro;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Esperado x contado do fechamento (tarefa 7.5; ADR-0012, itens 3.6 e 6), sem banco. Por peça: esperado = o saldo de cada maleta
 * do acerto + as peças "para a loja" das devoluções do vendedor; contado = a soma das leituras (boa e com defeito).
 *
 * <p>Contado maior que esperado é <strong>sobra</strong> (impede o fechamento). Contado menor é <strong>falta</strong>: o contado
 * enche primeiro a linha "para a loja" e depois as maletas da mais nova para a mais antiga, então a falta cai na maleta mais antiga
 * primeiro. A falta vale o preço da maleta onde caiu (na linha da loja, o valor pago pela peça devolvida).
 */
public final class EsperadoEContado {

    /** O saldo de uma peça numa maleta do acerto (pelos movimentos). {@code enviadaEm} ordena as maletas. */
    public record SaldoDeMaleta(UUID remessaId, Instant enviadaEm, UUID produtoId, int saldo, BigDecimal precoRemessa) {
    }

    /** As peças "para a loja" de uma peça: a quantidade (boas e com defeito) e o valor pago por elas, somados. */
    public record PecasParaALoja(UUID produtoId, int quantidade, BigDecimal valorTotal) {
    }

    /** O que se contou da peça: a soma das leituras boas e a das com defeito. */
    public record Contagem(UUID produtoId, int boa, int defeito) {
    }

    /** Uma linha de {@code itens_acerto}. {@code remessaId} e {@code precoRemessa} vazios = linha "peças para a loja". */
    public record Item(UUID produtoId, UUID remessaId, BigDecimal precoRemessa, int esperado, int contadoBoa, int contadoDefeito,
            int falta, int defeitoAprovado, BigDecimal valorDaFalta) {
    }

    public record Sobra(UUID produtoId, int esperado, int contado) {
    }

    public record Resultado(List<Item> itens, List<Sobra> sobras) {
    }

    private record Vaga(UUID remessaId, Instant enviadaEm, BigDecimal preco, int capacidade, BigDecimal valorTotalDaLoja) {
    }

    private EsperadoEContado() {
    }

    /**
     * @param aprovadas por peça, quantas das com defeito a dona aprovou (o resto volta como boa); o serviço confere o limite
     */
    public static Resultado calcular(List<SaldoDeMaleta> saldos, List<PecasParaALoja> loja, List<Contagem> contagens,
            Map<UUID, Integer> aprovadas) {
        Map<UUID, List<Vaga>> vagas = new TreeMap<>();
        Map<UUID, int[]> contado = new HashMap<>();
        for (PecasParaALoja l : loja) {
            if (l.quantidade() <= 0) {
                continue;
            }
            List<Vaga> daPeca = vagas.computeIfAbsent(l.produtoId(), k -> new ArrayList<>());
            Vaga existente = daPeca.stream().filter(v -> v.remessaId() == null).findFirst().orElse(null);
            if (existente == null) {
                daPeca.add(new Vaga(null, null, null, l.quantidade(), l.valorTotal()));
            } else {
                daPeca.remove(existente);
                daPeca.add(new Vaga(null, null, null, existente.capacidade() + l.quantidade(),
                        Dinheiro.somar(List.of(existente.valorTotalDaLoja(), l.valorTotal()))));
            }
        }
        for (SaldoDeMaleta s : saldos) {
            if (s.saldo() > 0) {
                vagas.computeIfAbsent(s.produtoId(), k -> new ArrayList<>())
                        .add(new Vaga(s.remessaId(), s.enviadaEm(), s.precoRemessa(), s.saldo(), null));
            }
        }
        for (Contagem c : contagens) {
            int[] soma = contado.computeIfAbsent(c.produtoId(), k -> new int[2]);
            soma[0] += c.boa();
            soma[1] += c.defeito();
            vagas.computeIfAbsent(c.produtoId(), k -> new ArrayList<>());
        }

        List<Item> itens = new ArrayList<>();
        List<Sobra> sobras = new ArrayList<>();
        for (Map.Entry<UUID, List<Vaga>> entrada : vagas.entrySet()) {
            UUID produto = entrada.getKey();
            List<Vaga> ordem = new ArrayList<>(entrada.getValue());
            ordem.sort(ORDEM_DE_PREENCHIMENTO);
            int esperado = ordem.stream().mapToInt(Vaga::capacidade).sum();
            int[] lido = contado.getOrDefault(produto, new int[2]);
            int total = lido[0] + lido[1];
            if (total > esperado) {
                sobras.add(new Sobra(produto, esperado, total));
            }
            int quantidadeDaLoja = ordem.stream().filter(v -> v.remessaId() == null).mapToInt(Vaga::capacidade).sum();
            int restoBoa = lido[0];
            int restoDefeito = lido[1];
            int restoAprovado = Math.min(aprovadas.getOrDefault(produto, 0), lido[1]);
            for (Vaga v : ordem) {
                int unidades = Math.min(v.capacidade(), restoBoa + restoDefeito);
                int boas = Math.min(unidades, restoBoa);
                int defeitos = unidades - boas;
                restoBoa -= boas;
                restoDefeito -= defeitos;
                int aprovados = Math.min(restoAprovado, defeitos);
                restoAprovado -= aprovados;
                int falta = v.capacidade() - unidades;
                BigDecimal valorDaFalta;
                if (falta == 0) {
                    valorDaFalta = Dinheiro.somar(List.of());
                } else if (v.remessaId() == null) {
                    valorDaFalta = Dinheiro.parteProporcional(v.valorTotalDaLoja(), falta, quantidadeDaLoja);
                } else {
                    valorDaFalta = Dinheiro.precoDaLinha(v.preco(), falta);
                }
                itens.add(new Item(produto, v.remessaId(), v.preco(), v.capacidade(), boas, defeitos, falta, aprovados, valorDaFalta));
            }
        }
        itens.sort(Comparator.comparing(Item::produtoId).thenComparing(i -> i.remessaId() != null));
        return new Resultado(List.copyOf(itens), List.copyOf(sobras));
    }

    /** A loja primeiro; depois as maletas da mais nova para a mais antiga (empate: o maior id primeiro). */
    private static final Comparator<Vaga> ORDEM_DE_PREENCHIMENTO = (a, b) -> {
        if (a.remessaId() == null || b.remessaId() == null) {
            return Boolean.compare(a.remessaId() != null, b.remessaId() != null);
        }
        int porData = b.enviadaEm().compareTo(a.enviadaEm());
        return porData != 0 ? porData : b.remessaId().compareTo(a.remessaId());
    };
}
