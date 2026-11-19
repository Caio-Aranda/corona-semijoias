package br.com.coronasemijoias.api.relatorios;

import br.com.coronasemijoias.api.consignado.ServicoDoFechamentoDaMaleta;
import br.com.coronasemijoias.api.consignado.ServicoDoFechamentoDaMaleta.LeituraDoFechamento;
import br.com.coronasemijoias.api.consignado.ServicoDoFechamentoDaMaleta.LinhaDaPeca;
import br.com.coronasemijoias.api.consignado.ServicoDoFechamentoDaMaleta.PecaComCliente;
import br.com.coronasemijoias.api.relatorios.RelatorioDeCadastro.Total;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * O relatório "Fechamento da maleta" (ESPECIFICACAO §23.10; ADR-0016, item 14), no formato de todo relatório da dona: linhas
 * paginadas e rodapé. É montado a partir da MESMA leitura da tela ({@link ServicoDoFechamentoDaMaleta#ler}), então a tela, o
 * PDF, o Excel e o CSV nunca discordam. Uma linha por peça da maleta; o rodapé traz as peças (enviadas, devolvidas pelos
 * clientes, vendidas, ainda com clientes, bipadas na volta e a diferença) e os valores (vendido, comissão dos clientes,
 * recebido e pendências a receber, divididos pelo vendido de cada maleta dentro da nota).
 */
@Component
public class RelatorioDoFechamentoDaMaleta {

    /** Uma peça da maleta. {@code comQuem} é o texto "Cliente (nota 65-32): 2", para a coluna do relatório. */
    public record LinhaDoFechamento(UUID id, String nome, int enviadas, int devolvidasPelosClientes, int vendidas, int comClientes,
            String comQuem, int esperadoNaMaleta, int bipadas, int diferenca) {
    }

    private final ServicoDoFechamentoDaMaleta fechamento;

    RelatorioDoFechamentoDaMaleta(ServicoDoFechamentoDaMaleta fechamento) {
        this.fechamento = fechamento;
    }

    public RelatorioDeCadastro<LinhaDoFechamento> montar(UUID remessaId, Integer pagina, Integer tamanho, String recorte) {
        LeituraDoFechamento leitura = fechamento.ler(remessaId);
        List<LinhaDoFechamento> inteiro = leitura.pecas().stream().map(RelatorioDoFechamentoDaMaleta::linha).toList();
        // "Ver os registros" de um total: só as linhas que o formam (o rodapé continua sendo o do relatório inteiro).
        List<LinhaDoFechamento> todas = inteiro.stream().filter(l -> formaOTotal(recorte, l)).toList();
        int tam = tamanho == null || tamanho < 1 ? MotorDeRelatorio.TAMANHO_PADRAO : Math.min(tamanho, MotorDeRelatorio.TAMANHO_MAXIMO);
        int pag = pagina == null || pagina < 0 ? 0 : pagina;
        int de = (int) Math.min((long) pag * tam, todas.size());
        List<LinhaDoFechamento> doRecorte = todas.subList(de, Math.min(de + tam, todas.size()));
        return new RelatorioDeCadastro<>(doRecorte, todas.size(), pag, tam, rodape(leitura, inteiro),
                recorte == null || recorte.isBlank() ? null : recorte);
    }

    private static boolean formaOTotal(String recorte, LinhaDoFechamento l) {
        if (recorte == null || recorte.isBlank()) {
            return true;
        }
        return switch (recorte) {
            case "enviadas" -> l.enviadas() != 0;
            case "devolvidas" -> l.devolvidasPelosClientes() != 0;
            case "vendidas" -> l.vendidas() != 0;
            case "comClientes" -> l.comClientes() != 0;
            case "bipadas" -> l.bipadas() != 0;
            case "diferenca" -> l.diferenca() != 0;
            default -> true;
        };
    }

    private static LinhaDoFechamento linha(LinhaDaPeca p) {
        String comQuem = p.aindaComClientes().stream()
                .map(c -> c.cliente() + " (nota " + numero(c) + "): " + c.quantidade())
                .collect(Collectors.joining("; "));
        return new LinhaDoFechamento(p.produtoId(), p.nome(), p.enviadas(), p.devolvidasPelosClientes(), p.vendidas(), p.comClientes(),
                comQuem, p.esperadoNaMaleta(), p.bipadas(), p.diferenca());
    }

    private static String numero(PecaComCliente c) {
        return c.semana() + "-" + c.sequencia();
    }

    private static List<Total> rodape(LeituraDoFechamento l, List<LinhaDoFechamento> linhas) {
        long n = linhas.size();
        List<Total> t = new ArrayList<>();
        t.add(pecas("enviadas", "Peças enviadas", n, linhas.stream().mapToLong(LinhaDoFechamento::enviadas).sum()));
        t.add(pecas("devolvidas", "Devolvidas pelos clientes", n, linhas.stream().mapToLong(LinhaDoFechamento::devolvidasPelosClientes).sum()));
        t.add(pecas("vendidas", "Vendidas", n, linhas.stream().mapToLong(LinhaDoFechamento::vendidas).sum()));
        t.add(pecas("comClientes", "Ainda com os clientes", n, linhas.stream().mapToLong(LinhaDoFechamento::comClientes).sum()));
        t.add(pecas("bipadas", "Lidas na volta", n, linhas.stream().mapToLong(LinhaDoFechamento::bipadas).sum()));
        t.add(pecas("diferenca", "Diferença (lidas menos esperadas)", n, linhas.stream().mapToLong(LinhaDoFechamento::diferenca).sum()));
        t.add(new Total("vd", "Valor vendido (VD)", n, l.valores().vd()));
        t.add(new Total("comissao", "Comissão dos clientes", n, l.valores().comissaoDosClientes()));
        t.add(new Total("recebido", "Valor recebido", n, l.valores().recebido()));
        t.add(new Total("pendencias", "Pendências a receber", n, l.valores().pendenciasAReceber()));
        return t;
    }

    private static Total pecas(String chave, String rotulo, long linhas, long pecas) {
        return new Total(chave, rotulo, linhas, null, pecas);
    }
}
