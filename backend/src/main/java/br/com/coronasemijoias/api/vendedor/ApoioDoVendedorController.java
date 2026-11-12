package br.com.coronasemijoias.api.vendedor;

import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.cotacoes.ServicoDeCotacoesDaDona;
import br.com.coronasemijoias.api.fotos.ServicoDeFotos;
import br.com.coronasemijoias.api.fotos.ServicoDeFotos.Baixada;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import br.com.coronasemijoias.api.seguranca.UsuarioAutenticado;
import br.com.coronasemijoias.api.vendedor.RelatoriosDoVendedor.Relatorio;
import br.com.coronasemijoias.api.vendedor.RelatoriosDoVendedor.RelatorioAReceber;
import io.swagger.v3.oas.annotations.Operation;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Cotação do dia, foto das peças da maleta e relatórios do vendedor (tarefa 4.3). Só VENDEDOR, só o que é dele. */
@RestController
@RequestMapping("/api/vendedor")
@ExigePerfil("VENDEDOR")
public class ApoioDoVendedorController {

    public record CotacaoDoVendedor(boolean disponivel, LocalDate dataDeHoje, LocalDate dataDaCotacao, String dolar,
            String guarani, boolean deDiaAnterior) {
    }

    private final ServicoDeCotacoesDaDona cotacoes;
    private final ServicoDeFotos fotos;
    private final RelatoriosDoVendedor relatorios;
    private final JdbcTemplate jdbc;

    ApoioDoVendedorController(ServicoDeCotacoesDaDona cotacoes, ServicoDeFotos fotos, RelatoriosDoVendedor relatorios,
            JdbcTemplate jdbc) {
        this.cotacoes = cotacoes;
        this.fotos = fotos;
        this.relatorios = relatorios;
        this.jdbc = jdbc;
    }

    @Operation(operationId = "verCotacaoDeHoje", summary = "Cotação do dia",
            description = "Reais por 1 Dólar e por 1 Guarani (taxa de venda) em vigor hoje, só para mostrar os valores nas três "
                    + "moedas. Sem nenhuma cotação ainda: disponivel = false (nunca é erro).")
    @GetMapping("/cotacao")
    public CotacaoDoVendedor cotacao() {
        LocalDate hoje = cotacoes.hoje();
        return cotacoes.cotacaoDeHoje()
                .map(c -> new CotacaoDoVendedor(true, hoje, c.dataCotacao(), c.taxas().usdVenda().toPlainString(),
                        c.taxas().pygVenda().toPlainString(), c.dataCotacao().isBefore(hoje)))
                .orElseGet(() -> new CotacaoDoVendedor(false, hoje, null, null, null, false));
    }

    @Operation(operationId = "baixarFotoDaMinhaMaleta", summary = "Foto de uma peça da minha maleta",
            description = "tamanho = miniatura, media ou grande. Só fotos de peças que estão em maletas do vendedor logado; "
                    + "as outras dão 404. O endereço leva o hash do conteúdo, então a resposta pode ficar no cache.")
    @GetMapping("/fotos/{hash}/{tamanho}")
    public ResponseEntity<byte[]> foto(@PathVariable String hash, @PathVariable String tamanho,
            @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String seTemOutro,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        boolean minha = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM corona.produtos_fotos f
                                 JOIN corona.itens_remessa i ON i.produto_id = f.produto_id AND i.deleted_at IS NULL
                                 JOIN corona.remessas r ON r.id = i.remessa_id AND r.deleted_at IS NULL
                                WHERE f.arquivo_hash = ? AND f.deleted_at IS NULL AND r.vendedor_id = ?
                                  AND r.status IN ('ENVIADA', 'CONFERIDA', 'EM_ACERTO'))""", Boolean.class, hash, quem.vendedorId()));
        if (!minha) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.ROTA_NAO_ENCONTRADA);
        }
        Baixada baixada = fotos.baixar(hash, tamanho);
        String etag = "\"" + baixada.hash() + "-" + baixada.tamanho().name().toLowerCase(Locale.ROOT) + "\"";
        CacheControl cache = CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable();
        if (seTemOutro != null && Arrays.stream(seTemOutro.split(",")).map(String::strip)
                .anyMatch(e -> e.equals(etag) || e.equals("*") || e.equals("W/" + etag))) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).cacheControl(cache)
                    .header("X-Content-Type-Options", "nosniff").build();
        }
        // O app confere este SHA-256 do tamanho baixado antes de guardar o arquivo (tarefa 5.9).
        return ResponseEntity.ok().eTag(etag).cacheControl(cache).header("X-Content-Type-Options", "nosniff")
                .header("X-Conteudo-Sha256", sha256(baixada.conteudo()))
                .header("Content-Security-Policy", "default-src 'none'; sandbox").contentType(MediaType.IMAGE_JPEG)
                .body(baixada.conteudo());
    }

    @Operation(operationId = "relatorioDeMinhasVendas", summary = "Minhas vendas por cliente, peça ou dia",
            description = "Só as vendas do vendedor logado, sem as estornadas, no período (de e até, horário de Brasília; ambos "
                    + "opcionais). agrupar = cliente (padrão), peca ou dia. Totais também em Dólar e Guarani pela cotação de hoje.")
    @GetMapping("/relatorios/vendas")
    public Relatorio vendas(@RequestParam(required = false) LocalDate de, @RequestParam(required = false) LocalDate ate,
            @RequestParam(required = false) String agrupar, @AuthenticationPrincipal UsuarioAutenticado quem) {
        periodoValido(de, ate);
        return relatorios.vendas(quem.vendedorId(), de, ate, agrupar);
    }

    @Operation(operationId = "relatorioDeMeusRecebimentos", summary = "Meus recebimentos por forma de pagamento",
            description = "O que os clientes do vendedor pagaram no período, por forma, já descontados os reembolsos.")
    @GetMapping("/relatorios/recebimentos")
    public Relatorio recebimentos(@RequestParam(required = false) LocalDate de, @RequestParam(required = false) LocalDate ate,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        periodoValido(de, ate);
        return relatorios.recebimentos(quem.vendedorId(), de, ate);
    }

    @Operation(operationId = "relatorioDeValoresAReceber", summary = "Quanto meus clientes ainda devem",
            description = "Por cliente do vendedor: vendido (sem estornos), pago e a receber; só quem deve.")
    @GetMapping("/relatorios/a-receber")
    public RelatorioAReceber aReceber(@AuthenticationPrincipal UsuarioAutenticado quem) {
        return relatorios.aReceber(quem.vendedorId());
    }

    private static void periodoValido(LocalDate de, LocalDate ate) {
        if (de != null && ate != null && de.isAfter(ate)) {
            throw Validacoes.recusar("de", "A data inicial não pode ser depois da final.");
        }
    }

    private static String sha256(byte[] conteudo) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(conteudo));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
