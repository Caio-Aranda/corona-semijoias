package br.com.coronasemijoias.api.estoque;

import br.com.coronasemijoias.api.cadastros.Mensagens;
import br.com.coronasemijoias.api.cadastros.Pagina;
import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.estoque.ConsultasDeEstoque.Ficha;
import br.com.coronasemijoias.api.estoque.ConsultasDeEstoque.Filtro;
import br.com.coronasemijoias.api.estoque.ConsultasDeEstoque.Linha;
import br.com.coronasemijoias.api.estoque.ConsultasDeEstoque.ProdutoResumo;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.Conciliacao;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.Divergencia;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.Movimento;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.OrigemDoMovimento;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.Pedido;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.Resultado;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.TipoDeMovimento;
import br.com.coronasemijoias.api.fotos.FotosDosProdutosController.FotoPrincipal;
import br.com.coronasemijoias.api.remessas.PecasEmMaletas;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import br.com.coronasemijoias.api.seguranca.UsuarioAutenticado;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
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
 * Estoque da dona (tarefa 2.2; ADR-0008): entradas e saídas, ajuste, "desfazer lançamento" (estorno), histórico
 * por produto e conferência. Só ADMIN. O saldo nunca é enviado pela tela: o servidor recalcula tudo.
 * Toda resposta que mostra um produto traz a foto principal ({@code fotoPrincipal}, vazia se não há foto).
 */
@RestController
@RequestMapping("/api/dona/estoque")
@ExigePerfil("ADMIN")
public class EstoqueDaDonaController {

    /** Entrada, saída ou perda. Remessa e devolução são da Fase 3; o ajuste tem rota própria. */
    public record PedidoDeMovimento(
            UUID id,
            @NotNull(message = Mensagens.OBRIGATORIO) UUID produtoId,
            @NotNull(message = Mensagens.OBRIGATORIO) String tipo,
            @NotNull(message = Mensagens.OBRIGATORIO) Integer quantidade,
            @Size(max = 500, message = Mensagens.LIMITE_LONGO) String motivo) {
    }

    /** Ajuste: mande {@code novaQuantidade} OU {@code diferenca} (com sinal), e o motivo. */
    public record PedidoDeAjuste(
            UUID id,
            @NotNull(message = Mensagens.OBRIGATORIO) UUID produtoId,
            Integer novaQuantidade,
            Integer diferenca,
            @Size(max = 500, message = Mensagens.LIMITE_LONGO) String motivo) {
    }

    public record PedidoDeEstorno(UUID id, @Size(max = 500, message = Mensagens.LIMITE_LONGO) String motivo) {
    }

    public record RespostaDoProduto(UUID id, String nome, String referencia, String codigo, int estoqueMinimo, boolean ativo,
            FotoPrincipal fotoPrincipal) {

        static RespostaDoProduto de(ProdutoResumo p) {
            return new RespostaDoProduto(p.id(), p.nome(), p.referencia(), p.codigo(), p.estoqueMinimo(), p.ativo(),
                    FotoPrincipal.de(p.fotoPrincipalHash()));
        }
    }

    /** Um movimento recém-gravado (ou o já gravado, num reenvio). {@code quantidade} tem sinal. */
    public record RespostaDoMovimento(UUID id, UUID produtoId, String tipo, int quantidade, int saldoDepois, String origem,
            UUID origemId, String motivo, UUID usuarioId, Instant ocorridaEm, UUID estornaId) {

        static RespostaDoMovimento de(Movimento m) {
            return new RespostaDoMovimento(m.id(), m.produtoId(), m.tipo().name(), m.quantidade(), m.saldoDepois(),
                    m.origem().name(), m.origemId(), m.motivo(), m.usuarioId(), m.ocorridaEm(), m.estornaId());
        }
    }

    /** Uma linha do histórico. {@code desfeitoPorId} diz qual lançamento o desfez (vazio se continua valendo). */
    public record RespostaDaLinha(UUID id, RespostaDoProduto produto, String tipo, int quantidade, int saldoDepois,
            String origem, UUID origemId, String motivo, UUID usuarioId, String usuarioNome, Instant ocorridaEm,
            UUID estornaId, UUID desfeitoPorId) {

        static RespostaDaLinha de(Linha l) {
            return new RespostaDaLinha(l.id(), RespostaDoProduto.de(l.produto()), l.tipo(), l.quantidade(), l.saldoDepois(),
                    l.origem(), l.origemId(), l.motivo(), l.usuarioId(), l.usuarioNome(), l.ocorridaEm(), l.estornaId(),
                    l.desfeitoPorId());
        }
    }

    /**
     * {@code emMaletas} e {@code vendedoresEmMaletas} separam "acabou de verdade" (saldo 0 e nenhuma em maleta) de "está
     * nas maletas" (item 13, 11/11/2026; só da dona).
     */
    public record RespostaDoAbaixoDoMinimo(RespostaDoProduto produto, int saldo, int minimo, int falta, int emMaletas,
            List<PecasEmMaletas.DoVendedor> vendedoresEmMaletas) {

        static RespostaDoAbaixoDoMinimo de(ConsultasDeEstoque.AbaixoDoMinimo a, PecasEmMaletas.Situacao maletas) {
            return new RespostaDoAbaixoDoMinimo(RespostaDoProduto.de(a.produto()), a.saldo(), a.minimo(), a.falta(),
                    maletas.total(), maletas.vendedores());
        }
    }

    public record RespostaDaFicha(RespostaDoProduto produto, int saldo, Pagina<RespostaDaLinha> historico, int emMaletas,
            List<PecasEmMaletas.DoVendedor> vendedoresEmMaletas) {
    }

    public record RespostaDaDivergencia(RespostaDoProduto produto, int saldoGuardado, long somaDosMovimentos, long diferenca) {
    }

    public record RespostaDaDiferencaComMaletas(RespostaDoProduto produto, int estoqueCentral, long saldoNasMaletas, long esperado,
            long diferenca) {
    }

    public record RespostaDaConciliacao(Instant geradaEm, long produtosConferidos, List<RespostaDaDivergencia> divergencias,
            List<RespostaDaDiferencaComMaletas> diferencasComMaletas) {
    }

    private final ServicoDeEstoque servico;
    private final ConsultasDeEstoque consultas;
    private final PecasEmMaletas emMaletas;

    public EstoqueDaDonaController(ServicoDeEstoque servico, ConsultasDeEstoque consultas, PecasEmMaletas emMaletas) {
        this.servico = servico;
        this.consultas = consultas;
        this.emMaletas = emMaletas;
    }

    @Operation(summary = "Lançar entrada, saída ou perda",
            description = "Tipos: ENTRADA, SAIDA e AVARIA (perda). Mande a quantidade positiva: o servidor põe o sinal. "
                    + "O id (UUID) é opcional e torna o reenvio seguro: o mesmo id com os mesmos dados devolve o lançamento "
                    + "já gravado (200) sem somar de novo; com dados diferentes, 409. Saída que deixaria o estoque abaixo de "
                    + "zero volta 409 dizendo quanto há. Produto inativo ainda pode ser lançado; excluído, não.")
    @PostMapping("/movimentacoes")
    public ResponseEntity<RespostaDoMovimento> lancar(@Valid @RequestBody PedidoDeMovimento pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        TipoDeMovimento tipo = tipoManual(pedido.tipo());
        if (pedido.quantidade() <= 0) {
            throw Validacoes.recusar("quantidade", "Informe uma quantidade maior que zero.");
        }
        Resultado r = servico.registrar(new Pedido(pedido.id(), pedido.produtoId(), tipo, pedido.quantidade(), pedido.motivo(),
                OrigemDoMovimento.MANUAL, null), quem.id());
        return resposta(r);
    }

    @Operation(summary = "Ajustar o estoque",
            description = "Corrige a quantidade e exige o motivo. Mande novaQuantidade (como o estoque deve ficar) OU "
                    + "diferenca (peças a mais, ou a menos se negativa), nunca os dois. Não passa de zero. Vai para a auditoria.")
    @PostMapping("/ajustes")
    public ResponseEntity<RespostaDoMovimento> ajustar(@Valid @RequestBody PedidoDeAjuste pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        if ((pedido.novaQuantidade() == null) == (pedido.diferenca() == null)) {
            throw Validacoes.recusar("novaQuantidade", "Informe a nova quantidade ou a diferença, só uma das duas.");
        }
        Resultado r = pedido.novaQuantidade() != null
                ? servico.ajustarPara(pedido.id(), pedido.produtoId(), pedido.novaQuantidade(), pedido.motivo(), quem.id())
                : servico.ajustarPorDiferenca(pedido.id(), pedido.produtoId(), pedido.diferenca(), pedido.motivo(), quem.id());
        return resposta(r);
    }

    @Operation(summary = "Desfazer um lançamento",
            description = "Cria um lançamento novo que cancela o anterior (o antigo continua no histórico). Exige o motivo. "
                    + "Recusa (409) desfazer duas vezes, desfazer um lançamento que já é um \"desfazer\" e o que deixaria o "
                    + "estoque abaixo de zero. Vai para a auditoria.")
    @PostMapping("/movimentacoes/{id}/estorno")
    public ResponseEntity<RespostaDoMovimento> desfazer(@PathVariable UUID id, @Valid @RequestBody PedidoDeEstorno pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        return resposta(servico.estornar(pedido.id(), id, pedido.motivo(), quem.id()));
    }

    @Operation(summary = "Entradas e saídas (histórico do estoque)",
            description = "Filtros: produtoId, tipo, origem (MANUAL, AJUSTE_MANUAL, COMPRA, INVENTARIO, MALETA, ESTORNO), usuarioId "
                    + "e período (de, ate: datas em UTC, as duas inclusivas). Mais novo primeiro. A página começa em 0 (até 200 por página).")
    @GetMapping("/movimentacoes")
    public Pagina<RespostaDaLinha> listar(@RequestParam(required = false) UUID produtoId,
            @RequestParam(required = false) String tipo, @RequestParam(required = false) String origem,
            @RequestParam(required = false) UUID usuarioId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) Integer pagina, @RequestParam(required = false) Integer tamanho) {
        return Pagina.de(consultas.listar(new Filtro(produtoId, tipo, origem, usuarioId, de, ate), pagina, tamanho),
                RespostaDaLinha::de);
    }

    @Operation(summary = "Produtos com estoque baixo",
            description = "Produtos ativos com estoque mínimo definido cujo saldo está no mínimo ou abaixo. Mostra a foto, "
                    + "quantas peças há (saldo), o mínimo e quantas faltam para chegar ao mínimo (falta). Quem mais falta vem "
                    + "primeiro. Filtros: categoriaId, fornecedorId e busca (nome, referência ou parte do código). "
                    + "O mínimo de cada peça se edita pela rota de produtos. A página começa em 0 (até 200 por página).")
    @GetMapping("/abaixo-do-minimo")
    public Pagina<RespostaDoAbaixoDoMinimo> abaixoDoMinimo(@RequestParam(required = false) UUID categoriaId,
            @RequestParam(required = false) UUID fornecedorId, @RequestParam(required = false) String busca,
            @RequestParam(required = false) Integer pagina, @RequestParam(required = false) Integer tamanho) {
        var lista = consultas.abaixoDoMinimo(categoriaId, fornecedorId, busca, pagina, tamanho);
        // Uma consulta só para a página inteira: nunca uma por linha.
        Map<UUID, PecasEmMaletas.Situacao> situacoes = emMaletas.de(
                lista.getContent().stream().map(a -> a.produto().id()).toList());
        return Pagina.de(lista, a -> RespostaDoAbaixoDoMinimo.de(a,
                situacoes.getOrDefault(a.produto().id(), PecasEmMaletas.Situacao.NENHUMA)));
    }

    @Operation(summary = "Ficha do produto no estoque",
            description = "Quantas peças há na loja, quantas estão nas maletas dos vendedores (e com quem) e o histórico do produto (mais novo primeiro).")
    @GetMapping("/produtos/{id}")
    public RespostaDaFicha ficha(@PathVariable UUID id, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        Ficha f = consultas.ficha(id, pagina, tamanho);
        PecasEmMaletas.Situacao maletas = emMaletas.de(f.produto().id());
        return new RespostaDaFicha(RespostaDoProduto.de(f.produto()), f.saldo(), Pagina.de(f.historico(), RespostaDaLinha::de),
                maletas.total(), maletas.vendedores());
    }

    @Operation(summary = "Conferir o estoque",
            description = "Para cada produto, compara o estoque guardado com a soma dos lançamentos (divergencias) e o estoque da loja "
                    + "mais o saldo das maletas enviadas com o que entrou e saiu fora da maleta (diferencasComMaletas). As duas "
                    + "listas devem vir vazias.")
    @GetMapping("/conciliacao")
    public RespostaDaConciliacao conciliar() {
        Conciliacao c = servico.conciliar();
        List<RespostaDaDivergencia> itens = c.divergencias().stream().map(this::divergencia).toList();
        List<RespostaDaDiferencaComMaletas> comMaletas = c.diferencasComMaletas().stream().map(d -> new RespostaDaDiferencaComMaletas(
                RespostaDoProduto.de(new ProdutoResumo(d.produtoId(), d.nome(), null, null, 0, true, d.fotoHash())),
                d.estoqueCentral(), d.saldoNasMaletas(), d.esperado(), d.diferenca())).toList();
        return new RespostaDaConciliacao(c.geradaEm(), c.produtosConferidos(), itens, comMaletas);
    }

    private RespostaDaDivergencia divergencia(Divergencia d) {
        ProdutoResumo produto = new ProdutoResumo(d.produtoId(), d.nome(), null, null, 0, true, d.fotoHash());
        return new RespostaDaDivergencia(RespostaDoProduto.de(produto), d.saldoGuardado(), d.somaDosMovimentos(), d.diferenca());
    }

    private static TipoDeMovimento tipoManual(String texto) {
        try {
            TipoDeMovimento tipo = TipoDeMovimento.valueOf(texto);
            if (tipo == TipoDeMovimento.ENTRADA || tipo == TipoDeMovimento.SAIDA || tipo == TipoDeMovimento.AVARIA) {
                return tipo;
            }
        } catch (IllegalArgumentException e) {
            // cai na recusa abaixo
        }
        throw Validacoes.recusar("tipo", "Escolha entrada, saída ou perda.");
    }

    private static ResponseEntity<RespostaDoMovimento> resposta(Resultado r) {
        return ResponseEntity.status(r.jaExistia() ? HttpStatus.OK : HttpStatus.CREATED).body(RespostaDoMovimento.de(r.movimento()));
    }
}
