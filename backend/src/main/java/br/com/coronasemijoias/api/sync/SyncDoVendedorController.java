package br.com.coronasemijoias.api.sync;

import br.com.coronasemijoias.api.aparelhos.ServicoDeAparelhos;
import br.com.coronasemijoias.api.aparelhos.SituacaoDoAparelho;
import br.com.coronasemijoias.api.cadastros.Mensagens;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.configuracoes.ChaveDeConfiguracao;
import br.com.coronasemijoias.api.configuracoes.Configuracoes;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import br.com.coronasemijoias.api.seguranca.UsuarioAutenticado;
import br.com.coronasemijoias.api.sync.ServicoDePull.ResumoDaTabela;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * O que o Corona Vendas recebe do servidor (tarefa 5.4; ADR-0013, itens 1, 3, 4, 5 e 6). Só o perfil VENDEDOR, só o
 * computador em uso ({@code InterceptadorDoAparelho}) e sempre com o vendedor do token, nunca do pedido.
 */
@RestController
@RequestMapping("/api/vendedor/sync")
@ExigePerfil("VENDEDOR")
public class SyncDoVendedorController {

    private static final Logger LOG = LoggerFactory.getLogger(SyncDoVendedorController.class);

    public static final int LIMITE_PADRAO = 500;

    /**
     * Uma linha recebida: a tabela, o id, a {@code version} (o app só troca a cópia se a recebida for maior), a lápide
     * ({@code deletedAt}) e as colunas da lista explícita da tabela. Dinheiro vai como texto com duas casas, datas em
     * ISO-8601 (UTC).
     */
    public record LinhaRecebida(String tabela, UUID id, int version, Instant deletedAt, Map<String, Object> campos) {
    }

    /** Valores da loja que o app precisa sem internet (lista explícita; ADR-0013, item 4). */
    public record ParametrosDoVendedor(int limiteDescontoPercentual, int tempoMaximoOfflineDias, String situacaoDoAparelho) {
    }

    public record RespostaDoPull(List<LinhaRecebida> linhas, String cursor, boolean temMais, Instant horaServidor,
            ParametrosDoVendedor parametros) {
    }

    public record TabelaConferida(String tabela, long quantidade, String resumo) {
    }

    public record RespostaDaConferencia(List<TabelaConferida> tabelas, Instant horaServidor) {
    }

    /** As tabelas em que o computador achou diferença na conferência por resumo (vira alerta de defeito). */
    public record DiferencasNaConferencia(
            @NotNull(message = Mensagens.OBRIGATORIO) @Size(max = 50, message = Mensagens.LIMITE_LONGO) List<String> tabelas) {
    }

    /** A resposta do envio: o resultado de cada operação, na ordem, mais a hora e os parâmetros (como no pull). */
    public record RespostaDoEnvio(List<ServicoDeEnvio.ResultadoDaOperacao> resultados, Instant horaServidor,
            ParametrosDoVendedor parametros, boolean apagarDados) {
    }

    private final ServicoDePull pull;
    private final ServicoDeEnvio envio;
    private final RegistroDeTabelas registro;
    private final ServicoDeAparelhos aparelhos;
    private final Configuracoes configuracoes;
    private final Clock relogio;

    SyncDoVendedorController(ServicoDePull pull, ServicoDeEnvio envio, RegistroDeTabelas registro, ServicoDeAparelhos aparelhos,
            Configuracoes configuracoes, Clock relogio) {
        this.pull = pull;
        this.envio = envio;
        this.registro = registro;
        this.aparelhos = aparelhos;
        this.configuracoes = configuracoes;
        this.relogio = relogio;
    }

    @Operation(operationId = "receberDados", summary = "Receber os dados do vendedor (pull)",
            description = "Devolve o que mudou desde o cursor, só do vendedor do token: maletas enviadas e itens, peças delas "
                    + "(com códigos e fotos), vendas, pagamentos, conferências, cotações, categorias, semanas e o diretório "
                    + "de clientes da loja. Cursor vazio é a carga inicial. Aplique cada linha só se a version for maior "
                    + "que a guardada. 409 CURSOR_INVALIDO: refaça a carga inicial sem tocar no que aguarda envio. "
                    + "Limite padrão 500 linhas, máximo 1000 (a expansão de uma maleta pode passar do limite).")
    @GetMapping("/pull")
    public RespostaDoPull receber(@AuthenticationPrincipal UsuarioAutenticado quem,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limite,
            @RequestParam(required = false) String tabela) {
        int tamanho = limite == null ? LIMITE_PADRAO : limite;
        if (tamanho < 1 || tamanho > ServicoDePull.LOTE_MAXIMO) {
            throw new ErroDaApi(HttpStatus.BAD_REQUEST, CodigoErro.PEDIDO_INVALIDO);
        }
        if (tabela != null && registro.sincronizavel(tabela).filter(t -> t.regra().declarada()).isEmpty()) {
            throw new ErroDaApi(HttpStatus.BAD_REQUEST, CodigoErro.PEDIDO_INVALIDO);
        }
        Instant agora = relogio.instant().truncatedTo(ChronoUnit.MILLIS);
        LoteDoPull lote;
        try {
            lote = pull.puxar(cursor, tamanho, new FiltroPorVendedor(quem.vendedorId()), tabela);
        } catch (CursorInvalidoException e) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.CURSOR_INVALIDO);
        }
        aparelhos.registrarPull(quem.dispositivoId());
        List<LinhaRecebida> linhas = new ArrayList<>(lote.linhas().size());
        for (LinhaDoPull l : lote.linhas()) {
            linhas.add(new LinhaRecebida(l.tabela(), l.id(), l.versao(), l.excluidaEm(), campos(l)));
        }
        return new RespostaDoPull(linhas, lote.proximoCursor(), lote.haMais(), agora, parametros(quem));
    }

    @Operation(operationId = "enviarOperacoes", summary = "Enviar as operações guardadas (push)",
            description = "Até 50 operações ou 512 KB, na ordem da fila do computador. Cada uma é processada numa transação "
                    + "própria e recebe ACEITO, JA_RECEBIDO (mesmo id e mesmo conteúdo), CONFLITO (mesmo id, outro conteúdo), "
                    + "RECUSADO (regra de negócio; a dona é avisada) ou NAO_PROCESSADO (passageiro: o lote parou ali). "
                    + "horaLocal é o relógio do computador no envio (corrige a hora das vendas); aguardandoDepoisDeste, "
                    + "quantas ainda ficam. 429 ENVIO_EM_ANDAMENTO: outro envio deste computador em andamento. 413 "
                    + "LOTE_GRANDE_DEMAIS: mande a metade. O computador trocado ainda envia o que guardou; terminando com "
                    + "nada aguardando, recebe apagarDados.")
    @PostMapping("/push")
    @br.com.coronasemijoias.api.aparelhos.AceitaComputadorTrocado
    public RespostaDoEnvio enviar(@AuthenticationPrincipal UsuarioAutenticado quem,
            @RequestBody ServicoDeEnvio.PedidoDeEnvio pedido, jakarta.servlet.http.HttpServletRequest requisicao) {
        if (requisicao.getContentLengthLong() > ServicoDeEnvio.MAXIMO_DE_BYTES) {
            throw new ErroDaApi(HttpStatus.CONTENT_TOO_LARGE, CodigoErro.LOTE_GRANDE_DEMAIS);
        }
        if (pedido == null) {
            throw new ErroDaApi(HttpStatus.BAD_REQUEST, CodigoErro.PEDIDO_INVALIDO);
        }
        ServicoDeEnvio.Envio feito = envio.enviar(quem, pedido);
        return new RespostaDoEnvio(feito.resultados(), relogio.instant().truncatedTo(ChronoUnit.MILLIS), parametros(quem),
                feito.apagarDados());
    }

    @Operation(operationId = "conferirDados", summary = "Conferência por resumo dos dados do vendedor",
            description = "Por tabela, quantas linhas são do vendedor (lápides incluídas) e o SHA-256 da lista ordenada "
                    + "\"id:version\" (uma por linha, com quebra de linha). O app compara com o que guardou depois de "
                    + "receber tudo; havendo diferença, recarrega a tabela e avisa em /conferencia/diferencas.")
    @GetMapping("/conferencia")
    public RespostaDaConferencia conferir(@AuthenticationPrincipal UsuarioAutenticado quem) {
        Instant agora = relogio.instant().truncatedTo(ChronoUnit.MILLIS);
        List<TabelaConferida> tabelas = new ArrayList<>();
        for (Map.Entry<String, ResumoDaTabela> e : pull.conferir(new FiltroPorVendedor(quem.vendedorId())).entrySet()) {
            if (registro.sincronizavel(e.getKey()).map(t -> t.regra().declarada()).orElse(false)) {
                tabelas.add(new TabelaConferida(e.getKey(), e.getValue().quantidade(), e.getValue().resumo()));
            }
        }
        return new RespostaDaConferencia(tabelas, agora);
    }

    @Operation(operationId = "avisarDiferencaNaConferencia", summary = "Avisar diferença na conferência por resumo",
            description = "O computador achou diferença entre o que guardou e o resumo do servidor: é defeito do filtro "
                    + "(ADR-0013, item 6). Fica no log como alerta; o app recarrega as tabelas.")
    @PostMapping("/conferencia/diferencas")
    public ResponseEntity<Void> avisarDiferenca(@AuthenticationPrincipal UsuarioAutenticado quem,
            @Valid @RequestBody DiferencasNaConferencia pedido) {
        List<String> conhecidas = pedido.tabelas().stream()
                .filter(t -> t != null && registro.sincronizavel(t).isPresent()).distinct().toList();
        LOG.error("DEFEITO DO FILTRO DO PULL: o computador {} do vendedor {} achou diferença na conferência por resumo em {}",
                quem.dispositivoId(), quem.vendedorId(), conhecidas);
        return ResponseEntity.noContent().build();
    }

    private ParametrosDoVendedor parametros(UsuarioAutenticado quem) {
        String situacao = aparelhos.situacao(quem.dispositivoId(), quem.id()).map(SituacaoDoAparelho::name).orElse(null);
        return new ParametrosDoVendedor(configuracoes.inteiro(ChaveDeConfiguracao.LIMITE_DESCONTO_PERCENTUAL),
                configuracoes.inteiro(ChaveDeConfiguracao.TEMPO_MAXIMO_OFFLINE_DIAS), situacao);
    }

    /** As colunas de negócio e as datas de controle, em tipos simples para o JSON (dinheiro e datas como texto). */
    private static Map<String, Object> campos(LinhaDoPull linha) {
        Map<String, Object> campos = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : linha.colunas().entrySet()) {
            String coluna = e.getKey();
            if ("id".equals(coluna) || "version".equals(coluna) || "deleted_at".equals(coluna)) {
                continue;
            }
            campos.put(coluna, simples(e.getValue()));
        }
        return campos;
    }

    private static Object simples(Object valor) {
        if (valor == null || valor instanceof String || valor instanceof Boolean || valor instanceof Integer
                || valor instanceof Long) {
            return valor;
        }
        if (valor instanceof BigDecimal dinheiro) {
            return dinheiro.toPlainString();
        }
        if (valor instanceof Instant || valor instanceof LocalDate || valor instanceof UUID) {
            return valor.toString();
        }
        if (valor instanceof Number numero) {
            return numero.longValue();
        }
        return valor.toString();
    }
}
