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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import br.com.coronasemijoias.api.seguranca.UsuarioAutenticado;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    static final String FONTE_DA_DONA = "Cotação da loja";
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /**
     * @param dolar         reais por 1 Dólar (taxa de venda), texto com 8 casas
     * @param guarani       reais por 1 Guarani (taxa de venda), texto com 8 casas; a tela mostra "por mil" (glossário, parte 8)
     * @param deDiaAnterior {@code true} quando a cotação é de um dia antes de hoje (fim de semana, feriado ou busca que falhou)
     * @param linha         a frase pronta para o rodapé da tela
     */
    public record CotacaoDoDia(
            UUID id, LocalDate dataCotacao, TaxaDeCotacao dolar, TaxaDeCotacao guarani, String fonte,
            Instant buscadaEm, boolean deDiaAnterior, String linha, String origem, Instant vigenteDesde,
            TaxaDeCotacao guaranisPorReal) {
    }

    /** O corpo de gravar e de prévia: os dois valores como texto com ponto (5.80 e 1350); {@code id} é opcional (reenvio). */
    public record NovaCotacaoPedido(UUID id, String usd, String guaranisPorReal) {
    }

    public record BancoCentralDoDia(LocalDate dataCotacao, TaxaDeCotacao dolar, String guaranisPorReal) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PreviaResposta(
            TaxaDeCotacao dolar, TaxaDeCotacao guaranisPorReal, TaxaDeCotacao guarani, BancoCentralDoDia bancoCentral,
            String diferencaDolarPorCento, String diferencaGuaraniPorCento, boolean avisoDeDiferenca, String aviso) {
    }

    public record GravadaResposta(CotacaoDoDia cotacao, boolean avisoDeDiferenca, String aviso) {
    }

    public record LinhaDoHistorico(
            UUID id, Instant vigenteDesde, String origem, TaxaDeCotacao dolar, TaxaDeCotacao guaranisPorReal,
            TaxaDeCotacao guarani, String lancadaPor, boolean valeAgora, String linha) {
    }

    public record HistoricoDoDia(LocalDate data, java.util.List<LinhaDoHistorico> cotacoes) {
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
    private final ServicoDeMudancaDeCotacao mudanca;

    public CotacoesDaDonaController(ServicoDeCotacoesDaDona servico, ServicoDeMudancaDeCotacao mudanca) {
        this.servico = servico;
        this.mudanca = mudanca;
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

    @Operation(
            summary = "Antes de gravar: a conversão e a comparação com o Banco Central",
            description = "Não grava nada. usd e guaranisPorReal são texto com ponto (\"5.80\", \"1350\"). Devolve o Guarani por real, o "
                    + "Banco Central do momento (se houver) e o aviso de diferença (a partir de 15%), que não trava.")
    @PostMapping("/previa")
    public PreviaResposta previa(@RequestBody NovaCotacaoPedido pedido) {
        return previaDe(mudanca.previa(pedido == null ? null : pedido.usd(), pedido == null ? null : pedido.guaranisPorReal()));
    }

    @Operation(
            summary = "A dona muda a cotação do Dólar e do Guarani",
            description = "Grava uma cotação nova, que vale a partir de agora (não muda o que já foi lançado). Um valor por moeda, "
                    + "para compra e venda. Dólar de R$ 0,01 a 999,99 (até 4 casas); Guarani por real de 1 a 99.999 (até 2 casas). "
                    + "O aviso de diferença do Banco Central não impede de gravar. Com id (gerado pela tela), o reenvio é "
                    + "idempotente: 201 na primeira vez, 200 depois; mesmo id com outros valores, 409.")
    @PostMapping
    public ResponseEntity<GravadaResposta> gravar(@RequestBody NovaCotacaoPedido pedido, @AuthenticationPrincipal UsuarioAutenticado quem) {
        ServicoDeMudancaDeCotacao.Resultado r = mudanca.gravar(
                pedido == null ? null : pedido.id(), pedido == null ? null : pedido.usd(),
                pedido == null ? null : pedido.guaranisPorReal(), quem.id());
        GravadaResposta corpo = new GravadaResposta(
                de(r.cotacao(), servico.hoje()), r.previa().avisoDeDiferenca(), r.previa().aviso());
        return ResponseEntity.status(r.jaExistia() ? HttpStatus.OK : HttpStatus.CREATED).body(corpo);
    }

    @Operation(
            summary = "As cotações de um dia",
            description = "Todas as cotações do dia (Banco Central e da loja), na ordem em que passaram a valer, com a que vale "
                    + "agora marcada. data é AAAA-MM-DD (hoje, se vier vazia). Nada se apaga nem se edita.")
    @GetMapping
    public HistoricoDoDia doDia(@RequestParam(required = false) LocalDate data) {
        LocalDate dia = data == null ? servico.hoje() : data;
        List<LinhaDoHistorico> linhas = mudanca.doDia(dia).stream().map(l -> {
            Cotacao c = l.cotacao();
            return new LinhaDoHistorico(c.id(), c.vigenteDesde(), c.origem(), new TaxaDeCotacao(c.taxas().usdVenda()),
                    c.guaranisPorReal() == null ? null : new TaxaDeCotacao(c.guaranisPorReal()),
                    new TaxaDeCotacao(c.taxas().pygVenda()), l.lancadaPor(), l.valeAgora(), linhaDa(c));
        }).toList();
        return new HistoricoDoDia(dia, linhas);
    }

    private static PreviaResposta previaDe(ServicoDeMudancaDeCotacao.Previa p) {
        BancoCentralDoDia bcb = p.bancoCentral().map(b -> new BancoCentralDoDia(
                b.dataCotacao(), new TaxaDeCotacao(b.dolar()), b.guaranisPorReal().toPlainString())).orElse(null);
        return new PreviaResposta(
                new TaxaDeCotacao(p.dolar()), new TaxaDeCotacao(p.guaranisPorReal()), new TaxaDeCotacao(p.guarani()), bcb,
                p.diferencaDolarPorCento(), p.diferencaGuaraniPorCento(), p.avisoDeDiferenca(), p.aviso());
    }

    private static CotacaoDoDia de(Cotacao c, LocalDate hoje) {
        return new CotacaoDoDia(
                c.id(), c.dataCotacao(), new TaxaDeCotacao(c.taxas().usdVenda()), new TaxaDeCotacao(c.taxas().pygVenda()),
                c.daDona() ? FONTE_DA_DONA : FONTE, c.buscadaEm(), c.dataCotacao().isBefore(hoje), linhaDa(c), c.origem(),
                c.vigenteDesde(), c.guaranisPorReal() == null ? null : new TaxaDeCotacao(c.guaranisPorReal()));
    }

    /** "Cotação do Banco Central de 06/11/2026" ou "Cotação da loja de 06/11/2026 às 10:15" (hora de Brasília). */
    static String linhaDa(Cotacao c) {
        if (c.daDona()) {
            return "Cotação da loja de " + DATA.format(c.dataCotacao()) + " às "
                    + HORA.format(c.vigenteDesde().atZone(CotacaoVigente.BRASILIA));
        }
        return "Cotação do Banco Central de " + DATA.format(c.dataCotacao());
    }
}
