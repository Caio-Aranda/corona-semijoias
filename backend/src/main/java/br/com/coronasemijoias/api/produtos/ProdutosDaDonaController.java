package br.com.coronasemijoias.api.produtos;

import br.com.coronasemijoias.api.cadastros.Mensagens;
import br.com.coronasemijoias.api.cadastros.Pagina;
import br.com.coronasemijoias.api.fotos.FotosDosProdutosController.FotoPrincipal;
import br.com.coronasemijoias.api.produtos.CodigosDosProdutos.Codigo;
import br.com.coronasemijoias.api.produtos.CodigosDosProdutos.Pedido;
import br.com.coronasemijoias.api.produtos.ServicoDeProdutos.Dados;
import br.com.coronasemijoias.api.produtos.ServicoDeProdutos.Detalhe;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import br.com.coronasemijoias.api.seguranca.UsuarioAutenticado;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Produtos, custos e códigos: cadastro da dona (tarefa 1.5). Só ADMIN. O preço de custo só aparece aqui;
 * não existe resposta de produto para o vendedor nesta tarefa. O estoque atual é só leitura (as
 * movimentações de estoque, na Fase 2, é que o mudam).
 */
@RestController
@RequestMapping("/api/dona/produtos")
@ExigePerfil("ADMIN")
public class ProdutosDaDonaController {

    /** Um código da peça. {@code id} só vai quando é um código que já existe. */
    public record PedidoDeCodigo(
            UUID id,
            @NotBlank(message = Mensagens.OBRIGATORIO) @Size(max = 60, message = Mensagens.LIMITE_CURTO) String codigo,
            @NotBlank(message = Mensagens.OBRIGATORIO) String tipo,
            UUID fornecedorId,
            Boolean principal,
            Boolean ativo) {

        Pedido paraServico() {
            return new Pedido(id, codigo, tipo, fornecedorId, principal, ativo);
        }
    }

    /** {@code id} (opcional) e {@code codigos} só ao criar; {@code version} (a que a tela leu) só ao editar. */
    public record PedidoDeProduto(
            UUID id,
            @NotBlank(message = Mensagens.OBRIGATORIO) @Size(max = 200, message = Mensagens.LIMITE_CURTO) String nome,
            @Size(max = 100, message = Mensagens.LIMITE_CURTO) String referencia,
            @NotNull(message = Mensagens.OBRIGATORIO) UUID categoriaId,
            UUID fornecedorId,
            @NotNull(message = Mensagens.OBRIGATORIO) BigDecimal precoVenda,
            @NotNull(message = Mensagens.OBRIGATORIO) BigDecimal precoCusto,
            Integer estoqueMinimo,
            Integer version,
            List<@Valid PedidoDeCodigo> codigos) {

        Dados dados() {
            return new Dados(nome, referencia, categoriaId, fornecedorId, precoVenda, precoCusto, estoqueMinimo);
        }
    }

    public record PedidoDosCodigos(Integer version, List<@Valid PedidoDeCodigo> codigos) {
    }

    public record RespostaDeCodigo(UUID id, String codigo, String tipo, UUID fornecedorId, boolean principal, boolean ativo) {

        static RespostaDeCodigo de(Codigo c) {
            return new RespostaDeCodigo(c.id(), c.codigo(), c.tipo(), c.fornecedorId(), c.principal(), c.ativo());
        }
    }

    public record Resposta(UUID id, int version, String nome, String referencia, UUID categoriaId, UUID fornecedorId,
            BigDecimal precoVenda, BigDecimal precoCusto, int estoqueMinimo, int estoqueAtual, boolean ativo,
            List<RespostaDeCodigo> codigos, FotoPrincipal fotoPrincipal) {

        static Resposta de(Detalhe d) {
            Produto p = d.produto();
            return new Resposta(p.getId(), p.getVersion(), p.getNome(), p.getReferencia(), p.getCategoriaId(),
                    p.getFornecedorId(), p.getPrecoVenda(), d.precoCusto(), p.getEstoqueMinimo(), p.getEstoqueAtual(),
                    p.isAtivo(), d.codigos().stream().map(RespostaDeCodigo::de).toList(),
                    FotoPrincipal.de(p.getFotoPrincipalHash()));
        }
    }

    public record CodigoGerado(String codigo) {
    }

    private final ServicoDeProdutos servico;

    public ProdutosDaDonaController(ServicoDeProdutos servico) {
        this.servico = servico;
    }

    @Operation(summary = "Listar produtos",
            description = "Busca pelo nome, pela referência ou por parte de qualquer código (sem diferenciar maiúsculas). "
                    + "Filtros: categoriaId, fornecedorId e ativo. A página começa em 0 (até 100 por página).")
    @GetMapping
    public Pagina<Resposta> listar(@RequestParam(required = false) String busca,
            @RequestParam(required = false) UUID categoriaId, @RequestParam(required = false) UUID fornecedorId,
            @RequestParam(required = false) Boolean ativo, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return Pagina.de(servico.listar(busca, categoriaId, fornecedorId, ativo, pagina, tamanho), Resposta::de);
    }

    @Operation(summary = "Achar o produto pelo código",
            description = "Procura o código inteiro (sem espaços nas pontas, sem diferenciar maiúsculas). Sem produto, 404.")
    @GetMapping("/por-codigo")
    public Resposta porCodigo(@RequestParam String codigo) {
        return Resposta.de(servico.porCodigo(codigo));
    }

    @GetMapping("/{id}")
    public Resposta ver(@PathVariable UUID id) {
        return Resposta.de(servico.ver(id));
    }

    @Operation(summary = "Gerar o próximo código da loja",
            description = "Para a peça sem etiqueta: o servidor entrega um número sequencial que ninguém mais recebe. "
                    + "Números pulados ficam sem uso.")
    @PostMapping("/codigos/proximo")
    public ResponseEntity<CodigoGerado> proximoCodigo() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new CodigoGerado(servico.proximoCodigoDaLoja()));
    }

    @Operation(summary = "Criar produto",
            description = "Grava o produto, o preço de custo e os códigos juntos. Precisa de pelo menos um código; "
                    + "o código não pode repetir (409 diz qual produto já o usa). O estoque atual não é aceito: começa em 0.")
    @PostMapping
    public ResponseEntity<Resposta> criar(@Valid @RequestBody PedidoDeProduto pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        List<Pedido> codigos = pedido.codigos() == null ? null : pedido.codigos().stream().map(PedidoDeCodigo::paraServico).toList();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Resposta.de(servico.criar(pedido.id(), pedido.dados(), codigos, quem.id())));
    }

    @Operation(summary = "Editar produto",
            description = "Mande a version que a tela leu; se outra pessoa alterou antes, volta 409. Os códigos "
                    + "mudam pela rota própria. Mudança de preço de venda ou de custo vai para a auditoria.")
    @PutMapping("/{id}")
    public Resposta editar(@PathVariable UUID id, @Valid @RequestBody PedidoDeProduto pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        return Resposta.de(servico.editar(id, pedido.version(), pedido.dados(), quem.id()));
    }

    @Operation(summary = "Alterar os códigos do produto",
            description = "A lista enviada passa a ser a lista do produto: o código com id é atualizado, o sem id entra "
                    + "e o que ficou de fora é excluído (e o código fica livre). Mande a version do produto.")
    @PutMapping("/{id}/codigos")
    public Resposta alterarCodigos(@PathVariable UUID id, @Valid @RequestBody PedidoDosCodigos pedido) {
        List<Pedido> codigos = pedido.codigos() == null ? null : pedido.codigos().stream().map(PedidoDeCodigo::paraServico).toList();
        return Resposta.de(servico.alterarCodigos(id, pedido.version(), codigos));
    }

    @Operation(summary = "Inativar produto", description = "Sai das escolhas, mas o histórico fica. Pode ser reativado.")
    @PostMapping("/{id}/inativar")
    public Resposta inativar(@PathVariable UUID id) {
        return Resposta.de(servico.definirAtivo(id, false));
    }

    @PostMapping("/{id}/reativar")
    public Resposta reativar(@PathVariable UUID id) {
        return Resposta.de(servico.definirAtivo(id, true));
    }

    @Operation(summary = "Excluir produto", description = "Exclusão lógica: o produto some das listas e os códigos dele ficam livres.")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> excluir(@PathVariable UUID id) {
        servico.excluir(id);
        return ResponseEntity.noContent().build();
    }
}
