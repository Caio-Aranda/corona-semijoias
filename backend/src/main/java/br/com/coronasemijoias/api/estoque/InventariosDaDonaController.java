package br.com.coronasemijoias.api.estoque;

import br.com.coronasemijoias.api.cadastros.Mensagens;
import br.com.coronasemijoias.api.cadastros.Pagina;
import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.estoque.ConsultasDeInventario.Detalhe;
import br.com.coronasemijoias.api.estoque.ConsultasDeInventario.Item;
import br.com.coronasemijoias.api.estoque.ConsultasDeInventario.Linha;
import br.com.coronasemijoias.api.estoque.ConsultasDeInventario.Resumo;
import br.com.coronasemijoias.api.estoque.EstoqueDaDonaController.RespostaDoProduto;
import br.com.coronasemijoias.api.estoque.ServicoDeInventario.Escopo;
import br.com.coronasemijoias.api.estoque.ServicoDeInventario.Resultado;
import br.com.coronasemijoias.api.estoque.ServicoDeInventario.ResultadoDaLeitura;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import br.com.coronasemijoias.api.seguranca.UsuarioAutenticado;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contagem do estoque da dona (tarefa 2.4; ADR-0008, item 8). Só ADMIN. O servidor recalcula tudo: o aplicativo manda
 * só o que foi lido. Toda peça mostrada traz a foto principal ({@code fotoPrincipal}, vazia se não há foto).
 */
@RestController
@RequestMapping("/api/dona/inventarios")
@ExigePerfil("ADMIN")
public class InventariosDaDonaController {

    public record PedidoParaComecar(UUID id, String escopo, UUID escopoId,
            @Size(max = 1000, message = Mensagens.LIMITE_LONGO) String observacoes) {
    }

    /** A peça vem pelo código OU pelo produtoId. {@code quantidade} é opcional (1 peça). */
    public record PedidoDeLeitura(UUID id, String codigo, UUID produtoId, Integer quantidade) {
    }

    public record PedidoDeContagem(UUID id, String codigo, UUID produtoId, Integer contado) {
    }

    public record PedidoParaFechar(Boolean zerarNaoLidos) {
    }

    public record PedidoParaCancelar(@Size(max = 500, message = Mensagens.LIMITE_LONGO) String motivo) {
    }

    public record RespostaDoResumo(int totalDePecas, int lidos, int naoLidos, int comDiferenca, long faltam, long sobram,
            long pecasEsperadas, long pecasContadas) {

        static RespostaDoResumo de(Resumo r) {
            return new RespostaDoResumo(r.totalDePecas(), r.lidos(), r.naoLidos(), r.comDiferenca(), r.faltam(), r.sobram(),
                    r.pecasEsperadas(), r.pecasContadas());
        }
    }

    public record RespostaDoItem(RespostaDoProduto produto, int esperado, Integer contado, Integer diferenca, int saldoAtual,
            boolean ajustada) {

        static RespostaDoItem de(Item i) {
            return new RespostaDoItem(RespostaDoProduto.de(i.produto()), i.esperado(), i.contado(), i.diferenca(), i.saldoAtual(),
                    i.ajustada());
        }
    }

    /** Peça com diferença que não foi ajustada porque foi excluída durante a contagem. */
    public record RespostaDaNaoAjustada(UUID produtoId, String nome, int esperado, int contado, int diferenca, String motivo) {
    }

    /** {@code ajustes} é quantos lançamentos de ajuste o fechamento gerou. */
    public record RespostaDaContagem(UUID id, String escopo, UUID escopoId, String escopoNome, String situacao, Instant iniciadoEm,
            String iniciadoPorNome, Instant fechadoEm, String fechadoPorNome, Boolean zerouNaoLidos, Instant canceladoEm,
            String motivoDoCancelamento, String observacoes, int ajustes, RespostaDoResumo resumo,
            java.util.List<RespostaDaNaoAjustada> naoAjustadas) {

        static RespostaDaContagem de(Detalhe d) {
            return new RespostaDaContagem(d.id(), d.escopo(), d.escopoId(), d.escopoNome(), d.situacao(), d.iniciadoEm(),
                    d.iniciadoPorNome(), d.fechadoEm(), d.fechadoPorNome(), d.zerouNaoLidos(), d.canceladoEm(),
                    d.motivoDoCancelamento(), d.observacoes(), d.ajustes(), RespostaDoResumo.de(d.resumo()),
                    d.naoAjustadas().stream().map(n -> new RespostaDaNaoAjustada(n.produtoId(), n.nome(), n.esperado(), n.contado(),
                            n.diferenca(), n.motivo())).toList());
        }
    }

    public record RespostaDaLinha(UUID id, String escopo, UUID escopoId, String escopoNome, String situacao, Instant iniciadoEm,
            Instant fechadoEm, int totalDePecas, int lidos) {

        static RespostaDaLinha de(Linha l) {
            return new RespostaDaLinha(l.id(), l.escopo(), l.escopoId(), l.escopoNome(), l.situacao(), l.iniciadoEm(), l.fechadoEm(),
                    l.totalDePecas(), l.lidos());
        }
    }

    /** A peça lida e a situação da contagem logo depois da leitura. */
    public record RespostaDaLeitura(RespostaDoItem item, RespostaDoResumo resumo) {
    }

    private final ServicoDeInventario servico;
    private final ConsultasDeInventario consultas;

    public InventariosDaDonaController(ServicoDeInventario servico, ConsultasDeInventario consultas) {
        this.servico = servico;
        this.consultas = consultas;
    }

    @Operation(summary = "Começar a contagem do estoque",
            description = "escopo: TODOS, CATEGORIA ou FORNECEDOR (com escopoId nos dois últimos). O servidor anota quantas peças "
                    + "esperava de cada produto nesse instante (esperado). Uma peça não pode estar em duas contagens em andamento: "
                    + "se alguma peça da seleção já está em outra, volta 409. O id (UUID) torna o reenvio seguro: mesmo id e mesmos "
                    + "dados devolve a contagem (200); outros dados, 409.")
    @PostMapping
    public ResponseEntity<RespostaDaContagem> comecar(@Valid @RequestBody PedidoParaComecar pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        Resultado r = servico.iniciar(pedido.id(), escopo(pedido.escopo()), pedido.escopoId(), pedido.observacoes(), quem.id());
        return resposta(r);
    }

    @Operation(summary = "Ler uma peça (soma ao contado)",
            description = "Mande o codigo (texto da etiqueta, sem diferenciar maiúsculas) OU o produtoId, e a quantidade (padrão 1). "
                    + "Cada leitura SOMA ao que já foi contado. O id (UUID) da leitura torna o reenvio seguro: mesmo id e mesmos "
                    + "dados não soma de novo (200); outros dados, 409. Peça de fora da contagem ou código que não existe volta 400.")
    @PostMapping("/{id}/leituras")
    public ResponseEntity<RespostaDaLeitura> ler(@PathVariable UUID id, @RequestBody PedidoDeLeitura pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        ResultadoDaLeitura r = servico.ler(id, pedido.id(), pedido.produtoId(), pedido.codigo(), pedido.quantidade(), quem.id());
        return leitura(r);
    }

    @Operation(summary = "Corrigir a contagem de uma peça",
            description = "Define quantas peças foram contadas (substitui o que estava; zero vale). Mande o codigo OU o produtoId. "
                    + "Mesmo id de leitura, mesmo efeito de reenvio das leituras.")
    @PostMapping("/{id}/contagens")
    public ResponseEntity<RespostaDaLeitura> corrigir(@PathVariable UUID id, @RequestBody PedidoDeContagem pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        ResultadoDaLeitura r = servico.definir(id, pedido.id(), pedido.produtoId(), pedido.codigo(), pedido.contado(), quem.id());
        return leitura(r);
    }

    @Operation(summary = "Fechar a contagem",
            description = "Ajusta o estoque nas peças com diferença (contado menos o esperado do começo), com o motivo \"Contagem do "
                    + "estoque\", tudo numa operação só. Entradas e saídas feitas durante a contagem continuam valendo. Peça que "
                    + "não foi lida só vira zero se zerarNaoLidos for verdadeiro; senão fica como está. Se algum ajuste deixaria uma "
                    + "peça com menos de zero, nada é gravado e volta 409 dizendo a peça. Peça excluída durante a contagem não recebe ajuste: vem em naoAjustadas, com a diferença. Fechar de novo devolve a contagem já fechada.")
    @PostMapping("/{id}/fechar")
    public RespostaDaContagem fechar(@PathVariable UUID id, @RequestBody(required = false) PedidoParaFechar pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        boolean zerar = pedido != null && Boolean.TRUE.equals(pedido.zerarNaoLidos());
        Resultado r = servico.fechar(id, zerar, quem.id());
        return RespostaDaContagem.de(consultas.ver(r.inventarioId()));
    }

    @Operation(summary = "Cancelar a contagem", description = "Não gera ajuste. Depois de fechada, não cancela (409). Cancelar de novo devolve a contagem.")
    @PostMapping("/{id}/cancelar")
    public RespostaDaContagem cancelar(@PathVariable UUID id, @Valid @RequestBody(required = false) PedidoParaCancelar pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        Resultado r = servico.cancelar(id, pedido == null ? null : pedido.motivo(), quem.id());
        return RespostaDaContagem.de(consultas.ver(r.inventarioId()));
    }

    @Operation(summary = "Ver a contagem", description = "Situação e totais: lidas, não lidas, com diferença, peças que faltam e que sobram.")
    @GetMapping("/{id}")
    public RespostaDaContagem ver(@PathVariable UUID id) {
        return RespostaDaContagem.de(consultas.ver(id));
    }

    @Operation(summary = "Lista de contagens", description = "Filtro: situacao (EM_ANDAMENTO, FECHADO ou CANCELADO). Mais nova primeiro. A página começa em 0 (até 200 por página).")
    @GetMapping
    public Pagina<RespostaDaLinha> listar(@RequestParam(required = false) String situacao, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return Pagina.de(consultas.listar(situacao, pagina, tamanho), RespostaDaLinha::de);
    }

    @Operation(summary = "Peças da contagem (esperado x contado)",
            description = "filtro: TODOS (padrão), DIVERGENTES (lidas com diferença), NAO_LIDOS ou LIDOS. busca: nome, referência ou parte "
                    + "do código. Cada peça traz a foto, o esperado do começo, o contado, a diferença e o saldo de agora.")
    @GetMapping("/{id}/itens")
    public Pagina<RespostaDoItem> itens(@PathVariable UUID id, @RequestParam(required = false) String filtro,
            @RequestParam(required = false) String busca, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return Pagina.de(consultas.itens(id, filtro, busca, pagina, tamanho), RespostaDoItem::de);
    }

    private ResponseEntity<RespostaDaContagem> resposta(Resultado r) {
        return ResponseEntity.status(r.jaExistia() ? HttpStatus.OK : HttpStatus.CREATED).body(RespostaDaContagem.de(consultas.ver(r.inventarioId())));
    }

    private ResponseEntity<RespostaDaLeitura> leitura(ResultadoDaLeitura r) {
        RespostaDaLeitura corpo = new RespostaDaLeitura(RespostaDoItem.de(consultas.item(r.inventarioId(), r.produtoId())),
                RespostaDoResumo.de(consultas.ver(r.inventarioId()).resumo()));
        return ResponseEntity.status(r.jaExistia() ? HttpStatus.OK : HttpStatus.CREATED).body(corpo);
    }

    private static Escopo escopo(String texto) {
        if (texto == null) {
            return null;
        }
        try {
            return Escopo.valueOf(texto);
        } catch (IllegalArgumentException e) {
            throw Validacoes.recusar("escopo", "Escolha todas as peças, uma categoria ou um fornecedor.");
        }
    }
}
