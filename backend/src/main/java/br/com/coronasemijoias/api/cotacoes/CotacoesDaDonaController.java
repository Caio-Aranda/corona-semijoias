package br.com.coronasemijoias.api.cotacoes;

import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cotação do dia e conversão para Dólar e Guarani, para a tela da dona (tarefa 1.7, ADR-0006). Nunca
 * trava a tela: sem cotação nenhuma a resposta é 200 com {@code disponivel = false}.
 */
@RestController
@RequestMapping("/api/dona/cotacoes")
@ExigePerfil("ADMIN")
public class CotacoesDaDonaController {

    static final String SEM_COTACAO =
            "Ainda não temos a cotação do Dólar e do Guarani. Os valores aparecem só em reais por enquanto.";
    static final String FONTE = "Banco Central do Brasil";
    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /**
     * @param dolar         reais por 1 Dólar (taxa de venda), texto com 8 casas
     * @param guarani       reais por 1 Guarani (taxa de venda), texto com 8 casas; a tela mostra "por mil" (glossário, parte 8)
     * @param deDiaAnterior {@code true} quando a cotação é de um dia antes de hoje (fim de semana, feriado ou busca que falhou)
     * @param linha         a frase pronta para o rodapé da tela
     */
    public record CotacaoDoDia(
            UUID id, LocalDate dataCotacao, TaxaDeCotacao dolar, TaxaDeCotacao guarani, String fonte,
            Instant buscadaEm, boolean deDiaAnterior, String linha) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CotacaoDeHojeResposta(boolean disponivel, LocalDate dataDeHoje, String mensagem, CotacaoDoDia cotacao) {
    }

    public record ConversaoPedido(
            @NotNull(message = "Preencha este campo.") BigDecimal valorEmReais, LocalDate data) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ConversaoResposta(
            boolean disponivel, String valorEmReais, String dolar, String guarani, LocalDate dataDaCotacao,
            String mensagem) {
    }

    private final ServicoDeCotacoesDaDona servico;

    public CotacoesDaDonaController(ServicoDeCotacoesDaDona servico) {
        this.servico = servico;
    }

    @Operation(
            summary = "Cotação do dia",
            description = "A cotação em vigor hoje (data de Brasília): a do próprio dia, ou a do último dia útil em fim de "
                    + "semana, feriado ou busca que falhou (deDiaAnterior). Sem nenhuma cotação ainda, "
                    + "responde 200 com disponivel = false e uma mensagem; nunca é erro.")
    @GetMapping("/hoje")
    public CotacaoDeHojeResposta hoje() {
        LocalDate hoje = servico.hoje();
        return servico.cotacaoDeHoje()
                .map(c -> new CotacaoDeHojeResposta(true, hoje, null, de(c, hoje)))
                .orElseGet(() -> new CotacaoDeHojeResposta(false, hoje, SEM_COTACAO, null));
    }

    @Operation(
            summary = "Converter um valor em Real para Dólar e Guarani",
            description = "Só para mostrar, nada é gravado. valorEmReais é texto decimal com ponto e até 2 casas (número JSON é recusado; pode ser "
                    + "negativo, para estorno); data é opcional (hoje, se vier vazia) e usa a cotação em vigor naquela "
                    + "data. Dólar com 2 casas e Guarani sem casas. Sem cotação: 200 com disponivel = false.")
    @PostMapping("/converter")
    public ConversaoResposta converter(@Valid @RequestBody ConversaoPedido pedido) {
        BigDecimal reais = pedido.valorEmReais().setScale(2);
        Optional<Cotacao> cotacao = servico.cotacaoDe(pedido.data());
        if (cotacao.isEmpty()) {
            return new ConversaoResposta(false, reais.toPlainString(), null, null, null, SEM_COTACAO);
        }
        ServicoDeCotacoesDaDona.Convertido convertido = servico.converter(cotacao.get(), reais);
        return new ConversaoResposta(
                true, reais.toPlainString(), convertido.dolar().toPlainString(),
                convertido.guarani().toPlainString(), cotacao.get().dataCotacao(), null);
    }

    private static CotacaoDoDia de(Cotacao c, LocalDate hoje) {
        return new CotacaoDoDia(
                c.id(), c.dataCotacao(), new TaxaDeCotacao(c.taxas().usdVenda()), new TaxaDeCotacao(c.taxas().pygVenda()),
                FONTE, c.buscadaEm(), c.dataCotacao().isBefore(hoje),
                "Cotação do Banco Central de " + DATA.format(c.dataCotacao()));
    }
}
