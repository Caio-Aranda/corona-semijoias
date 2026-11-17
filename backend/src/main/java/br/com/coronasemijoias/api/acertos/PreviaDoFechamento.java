package br.com.coronasemijoias.api.acertos;

import br.com.coronasemijoias.api.acertos.CalculoDaComissao.Linha;
import br.com.coronasemijoias.api.acertos.EsperadoEContado.Sobra;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A prévia do fechamento do acerto, com tipo no OpenAPI (tarefa 8.11). Os campos, os nomes e o conteúdo são os mesmos do mapa que
 * a prévia devolvia antes (um teste compara o JSON de antes e de depois na mesma massa); só ganha {@code faltaParaMetaGuarani} e
 * {@code faltaParaMetaReais}, a mesma conta da prévia do vendedor ({@link ServicoDaComissao#faltaParaMeta}). Nada é gravado.
 *
 * <p>Os registros aninhados têm nomes que não se repetem no código, para o esquema do OpenAPI não ser renomeado.
 */
public record PreviaDoFechamento(UUID acertoId, List<PecaDaPreviaDoFechamento> itens, List<Sobra> sobras,
        List<FaltaDaPreviaDoFechamento> faltas, List<DefeitoDaPreviaDoFechamento> defeitos, List<EventoDaPreviaDoFechamento> eventos,
        List<RevisaoDaPreviaDoFechamento> revisoesPendentes, List<NaoAceitaDaPreviaDoFechamento> naoAceitas,
        List<AntigaDaPreviaDoFechamento> pendenciasAntigas, List<Linha> comissoes, long metaGuarani, long vendidoParaMetaGuarani,
        long faltaParaMetaGuarani, BigDecimal faltaParaMetaReais, BigDecimal percentualComissao, BigDecimal vendidoBruto,
        BigDecimal descontos, BigDecimal devolvido, BigDecimal vendidoLiquido, BigDecimal comissao, BigDecimal faltasCobradas,
        BigDecimal liquido, BigDecimal creditoDisponivel, BigDecimal creditoUsado, BigDecimal aPagarNoAcerto,
        BigDecimal recebidoNoAcerto, BigDecimal boleto, BigDecimal ficouDevendo, BigDecimal pagoAoVendedor, boolean restanteSemData,
        List<ParcelaDoRestanteDaPrevia> parcelasDoRestante, List<ContaAbertaNaPrevia> contasAbertasDoVendedor, UUID cotacaoId,
        LocalDate cotacaoData, boolean podeFechar) {

    /** A peça com um nome só para a prévia (id, nome, código e foto). */
    public record ProdutoDaPreviaDoFechamento(UUID id, String nome, String codigo, String fotoHash) {
    }

    /** Uma peça numa maleta: o esperado, o contado e a falta. */
    public record PecaDaPreviaDoFechamento(ProdutoDaPreviaDoFechamento produto, UUID remessaId, int esperado, int contadoBoa,
            int contadoDefeito, int falta, BigDecimal valorDaFalta) {
    }

    /** Uma falta, com o valor e como ficaria. */
    public record FaltaDaPreviaDoFechamento(ProdutoDaPreviaDoFechamento produto, UUID remessaId, Integer quantidade, BigDecimal valor,
            String status) {
    }

    /** Peças com defeito contadas. */
    public record DefeitoDaPreviaDoFechamento(ProdutoDaPreviaDoFechamento produto, Integer contadoDefeito) {
    }

    /** Um evento (venda, estorno ou devolução) que entra no acerto. */
    public record EventoDaPreviaDoFechamento(String tipo, UUID origemId, UUID vendaId, BigDecimal valor) {
    }

    /** O que está em revisão e impede o fechamento. */
    public record RevisaoDaPreviaDoFechamento(String tipo, UUID id) {
    }

    /** O que a dona não aceitou e vira cobrança do vendedor. */
    public record NaoAceitaDaPreviaDoFechamento(String tipo, String origemTipo, UUID origemId, BigDecimal valor) {
    }

    /** Uma pendência de acerto antigo ainda aberta. */
    public record AntigaDaPreviaDoFechamento(UUID id, String tipo, UUID produtoId, Integer quantidade, BigDecimal valor) {
    }

    /** Uma parcela que o restante geraria (só para mostrar). */
    public record ParcelaDoRestanteDaPrevia(int numero, LocalDate vencimento, BigDecimal valor) {
    }

    /** Uma conta que o vendedor já devia à loja. */
    public record ContaAbertaNaPrevia(UUID id, String origem, int numero, int totalDeParcelas, LocalDate vencimento, BigDecimal valor,
            BigDecimal emAberto, String formaPrevista) {
    }
}
