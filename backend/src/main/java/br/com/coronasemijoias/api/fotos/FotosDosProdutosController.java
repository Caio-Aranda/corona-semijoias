package br.com.coronasemijoias.api.fotos;

import br.com.coronasemijoias.api.cadastros.Pagina;
import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.fotos.ImportacaoDeFotos.Previa;
import br.com.coronasemijoias.api.fotos.ImportacaoDeFotos.Relatorio;
import br.com.coronasemijoias.api.fotos.RelatorioDeProdutosSemFoto.Item;
import br.com.coronasemijoias.api.fotos.ServicoDeFotos.Baixada;
import br.com.coronasemijoias.api.fotos.ServicoDeFotos.Foto;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import io.swagger.v3.oas.annotations.Operation;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Fotos de produto (tarefa 1.6; §20). Só ADMIN (a dona): o vendedor não cadastra produto nem mexe nas
 * fotos. As imagens só saem por rota autenticada, com cache pelo hash.
 */
@RestController
@RequestMapping("/api/dona/produtos")
@ExigePerfil("ADMIN")
public class FotosDosProdutosController {

    /** Onde ficam os três tamanhos de uma foto (a rota de download). */
    public static final String CAMINHO_DAS_IMAGENS = "/api/dona/produtos/fotos";

    /** A foto principal na leitura do produto: o hash e o endereço dos três tamanhos. */
    public record FotoPrincipal(String hash, String miniaturaUrl, String mediaUrl, String grandeUrl) {

        /** {@code null} quando o produto não tem foto (a falta de foto nunca é erro). */
        public static FotoPrincipal de(String hash) {
            return hash == null ? null : new FotoPrincipal(hash, url(hash, "miniatura"), url(hash, "media"), url(hash, "grande"));
        }
    }

    public record RespostaDaFoto(UUID id, UUID produtoId, String hash, boolean principal, int ordem, int largura,
            int altura, int tamanhoBytes, int version, String miniaturaUrl, String mediaUrl, String grandeUrl) {

        static RespostaDaFoto de(Foto f) {
            return new RespostaDaFoto(f.id(), f.produtoId(), f.hash(), f.principal(), f.ordem(), f.largura(), f.altura(),
                    f.tamanhoBytes(), f.version(), url(f.hash(), "miniatura"), url(f.hash(), "media"), url(f.hash(), "grande"));
        }
    }

    public record PedidoDeOrdem(List<UUID> ids) {
    }

    public record PedidoDePrevia(List<String> nomes) {
    }

    private static String url(String hash, String tamanho) {
        return CAMINHO_DAS_IMAGENS + "/" + hash + "/" + tamanho;
    }

    private final ServicoDeFotos fotos;
    private final ImportacaoDeFotos importacao;
    private final RelatorioDeProdutosSemFoto semFoto;

    public FotosDosProdutosController(ServicoDeFotos fotos, ImportacaoDeFotos importacao, RelatorioDeProdutosSemFoto semFoto) {
        this.fotos = fotos;
        this.importacao = importacao;
        this.semFoto = semFoto;
    }

    // --- fotos de uma peça ---

    @Operation(summary = "Listar as fotos da peça", description = "Na ordem da tela; a principal vem marcada. Até 5 fotos.")
    @GetMapping("/{id}/fotos")
    public List<RespostaDaFoto> listar(@PathVariable UUID id) {
        return fotos.listar(id).stream().map(RespostaDaFoto::de).toList();
    }

    @Operation(summary = "Enviar uma foto da peça",
            description = "Formulário com o campo 'arquivo'. Aceita JPG, PNG e WebP de até 10 MB; o tipo vale pelo "
                    + "conteúdo, não pelo nome. O servidor corrige a rotação, tira os dados escondidos (como o local) "
                    + "e gera miniatura, tamanho médio e grande. A primeira foto vira a principal. Enviar de novo a "
                    + "mesma foto não duplica (responde 200 com a que já existe). Uma peça tem no máximo 5 fotos.")
    @PostMapping(path = "/{id}/fotos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<RespostaDaFoto> enviar(@PathVariable UUID id,
            @RequestPart(name = "arquivo", required = false) MultipartFile arquivo) throws IOException {
        if (arquivo == null || arquivo.isEmpty()) {
            throw Validacoes.recusar("arquivo", "Este arquivo está vazio. Escolha outro.");
        }
        ServicoDeFotos.Enviada enviada = fotos.adicionar(id, arquivo.getBytes());
        return ResponseEntity.status(enviada.nova() ? HttpStatus.CREATED : HttpStatus.OK).body(RespostaDaFoto.de(enviada.foto()));
    }

    @Operation(summary = "Escolher a foto principal")
    @PostMapping("/{id}/fotos/{fotoId}/principal")
    public List<RespostaDaFoto> principal(@PathVariable UUID id, @PathVariable UUID fotoId) {
        return fotos.definirPrincipal(id, fotoId).stream().map(RespostaDaFoto::de).toList();
    }

    @Operation(summary = "Mudar a ordem das fotos",
            description = "Mande os ids de todas as fotos da peça, uma vez cada, na ordem nova. A principal não muda.")
    @PutMapping("/{id}/fotos/ordem")
    public List<RespostaDaFoto> ordem(@PathVariable UUID id, @RequestBody PedidoDeOrdem pedido) {
        return fotos.reordenar(id, pedido == null ? null : pedido.ids()).stream().map(RespostaDaFoto::de).toList();
    }

    @Operation(summary = "Excluir uma foto",
            description = "Exclusão lógica. Se era a principal, a próxima da ordem passa a ser a principal.")
    @DeleteMapping("/{id}/fotos/{fotoId}")
    public ResponseEntity<Void> excluir(@PathVariable UUID id, @PathVariable UUID fotoId) {
        fotos.excluir(id, fotoId);
        return ResponseEntity.noContent().build();
    }

    // --- imagem ---

    @Operation(summary = "Baixar um tamanho da foto",
            description = "tamanho = miniatura (cerca de 150 pontos), media (600) ou grande (1600). Só com login. "
                    + "O endereço leva o hash do conteúdo, então a resposta pode ficar no cache do aplicativo "
                    + "(Cache-Control e ETag). Foto excluída ou hash desconhecido: 404.")
    @GetMapping("/fotos/{hash}/{tamanho}")
    public ResponseEntity<byte[]> imagem(@PathVariable String hash, @PathVariable String tamanho,
            @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String seTemOutro) {
        Baixada baixada = fotos.baixar(hash, tamanho);
        String etag = "\"" + baixada.hash() + "-" + baixada.tamanho().name().toLowerCase(java.util.Locale.ROOT) + "\"";
        ResponseEntity.BodyBuilder resposta = ResponseEntity.status(HttpStatus.OK)
                .eTag(etag)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .contentType(MediaType.IMAGE_JPEG);
        if (seTemOutro != null && java.util.Arrays.stream(seTemOutro.split(",")).map(String::strip)
                .anyMatch(e -> e.equals(etag) || e.equals("*") || e.equals("W/" + etag))) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag)
                    .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable())
                    .header("X-Content-Type-Options", "nosniff").build();
        }
        return resposta.body(baixada.conteudo());
    }

    // --- importação em massa ---

    @Operation(summary = "Importação de fotos: prévia",
            description = "Recebe só os NOMES dos arquivos (nada é enviado nem gravado). O nome sem a extensão é o "
                    + "código da peça (sem diferenciar maiúsculas). Devolve as fotos associadas, as sem peça, os nomes "
                    + "repetidos para a mesma peça e as peças que ficarão sem foto.")
    @PostMapping("/fotos/importacao/previa")
    public Previa previa(@RequestBody PedidoDePrevia pedido) {
        return importacao.previa(pedido == null ? null : pedido.nomes());
    }

    @Operation(summary = "Importação de fotos: confirmar",
            description = "Formulário com vários campos 'arquivos' (até 10 por vez; mande a pasta em lotes). Cada foto "
                    + "é gravada sozinha: uma que falha não derruba as outras, e a resposta diz o que aconteceu com "
                    + "cada uma. Repetir o mesmo lote não duplica nada.")
    @PostMapping(path = "/fotos/importacao/confirmar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Relatorio confirmar(@RequestPart(name = "arquivos", required = false) List<MultipartFile> arquivos) {
        return importacao.confirmar(arquivos);
    }

    // --- relatório ---

    @Operation(summary = "Produtos sem foto",
            description = "Relatório das peças sem nenhuma foto. Por padrão só as ativas (ativo=false mostra as inativas). "
                    + "Filtros: busca, categoriaId, fornecedorId. A página começa em 0 (até 100 por página).")
    @GetMapping("/sem-foto")
    public Pagina<Item> semFoto(@RequestParam(required = false) String busca, @RequestParam(required = false) UUID categoriaId,
            @RequestParam(required = false) UUID fornecedorId, @RequestParam(required = false) Boolean ativo,
            @RequestParam(required = false) Integer pagina, @RequestParam(required = false) Integer tamanho) {
        return semFoto.listar(busca, categoriaId, fornecedorId, ativo, pagina, tamanho);
    }
}
