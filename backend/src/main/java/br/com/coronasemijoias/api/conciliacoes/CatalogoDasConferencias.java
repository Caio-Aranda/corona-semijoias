package br.com.coronasemijoias.api.conciliacoes;

import br.com.coronasemijoias.api.conciliacoes.ConferenciaDaRodada.LinhaDeDiferenca;
import br.com.coronasemijoias.api.conciliacoes.ConciliacoesDaFase7.Conferencia;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque;
import br.com.coronasemijoias.api.relatorios.ConferenciasDeDevolucaoETroca;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * As treze conferências da rodada diária, em ordem fixa (tarefa 8.4): estoque (ADR-0008), devolução e troca (6.10) e C1 a C5 e C7 a
 * C12 (a C6 está vaga), uma chave para cada, para que o erro de uma não derrube as outras. Cada uma só chama a classe de produção
 * que já existe: a fórmula é uma só, a mesma da rota de cada conferência.
 */
@Component
public class CatalogoDasConferencias {

    private final ConciliacoesDaFase7 fase7;
    private final ServicoDeEstoque estoque;
    private final ConferenciasDeDevolucaoETroca devolucaoETroca;

    public CatalogoDasConferencias(ConciliacoesDaFase7 fase7, ServicoDeEstoque estoque, ConferenciasDeDevolucaoETroca devolucaoETroca) {
        this.fase7 = fase7;
        this.estoque = estoque;
        this.devolucaoETroca = devolucaoETroca;
    }

    /** As conferências, na ordem em que rodam e são guardadas. */
    public List<ConferenciaDaRodada> todas() {
        List<ConferenciaDaRodada> todas = new ArrayList<>();
        todas.add(de("ESTOQUE", this::doEstoque));
        todas.add(de("DEVOLUCAO_E_TROCA", this::daDevolucaoETroca));
        for (String chave : ConciliacoesDaFase7.CHAVES) {
            todas.add(de(chave, () -> daFase7(chave)));
        }
        return todas;
    }

    public static ConferenciaDaRodada de(String chave, Supplier<List<LinhaDeDiferenca>> conferencia) {
        return new ConferenciaDaRodada() {
            @Override
            public String chave() {
                return chave;
            }

            @Override
            public List<LinhaDeDiferenca> conferir() {
                return conferencia.get();
            }
        };
    }

    private List<LinhaDeDiferenca> daFase7(String chave) {
        List<LinhaDeDiferenca> linhas = new ArrayList<>();
        for (Conferencia c : fase7.conferir(chave).conferencias()) {
            c.divergencias().forEach(d -> linhas.add(new LinhaDeDiferenca(null, d.tipo(),
                    d.referencia() == null ? null : d.referencia().toString(), d.descricao(), texto(d.esperado()),
                    texto(d.encontrado()), d.unidade() == null ? null : d.unidade().name())));
        }
        return linhas;
    }

    private List<LinhaDeDiferenca> doEstoque() {
        List<LinhaDeDiferenca> linhas = new ArrayList<>();
        ServicoDeEstoque.Conciliacao c = estoque.conciliar();
        c.divergencias().forEach(d -> linhas.add(new LinhaDeDiferenca("SALDO", "PRODUTO", d.produtoId().toString(),
                d.nome() + ": o saldo guardado não bate com a soma dos movimentos.", Long.toString(d.somaDosMovimentos()),
                Integer.toString(d.saldoGuardado()), "PECAS")));
        c.diferencasComMaletas().forEach(d -> linhas.add(new LinhaDeDiferenca("MALETAS", "PRODUTO", d.produtoId().toString(),
                d.nome() + ": o estoque da loja mais as maletas não bate com o que entrou e saiu.", Long.toString(d.esperado()),
                Long.toString(d.estoqueCentral() + d.saldoNasMaletas()), "PECAS")));
        return linhas;
    }

    private List<LinhaDeDiferenca> daDevolucaoETroca() {
        List<LinhaDeDiferenca> linhas = new ArrayList<>();
        for (ConferenciasDeDevolucaoETroca.Conferencia c : devolucaoETroca.conferir().conferencias()) {
            c.divergencias().forEach(d -> linhas.add(new LinhaDeDiferenca(c.chave(), null, d.referencia(), d.descricao(), null, null, null)));
        }
        return linhas;
    }

    private static String texto(BigDecimal valor) {
        return valor == null ? null : valor.toPlainString();
    }
}
