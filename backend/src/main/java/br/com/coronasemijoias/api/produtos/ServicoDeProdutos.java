package br.com.coronasemijoias.api.produtos;

import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria;
import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria.Ator;
import br.com.coronasemijoias.api.cadastros.ServicoDeCadastro;
import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.produtos.CodigosDosProdutos.Codigo;
import br.com.coronasemijoias.api.produtos.CodigosDosProdutos.Pedido;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Produtos da dona (tarefa 1.5). O produto e o custo são gravados na mesma transação; o custo e o
 * preço de venda mudam com registro na auditoria (antes e depois). Quem chama é sempre a dona: o
 * custo nunca sai por aqui para o vendedor.
 */
@Service
public class ServicoDeProdutos extends ServicoDeCadastro<Produto> {

    /** Os campos de um produto como a dona preencheu. */
    public record Dados(String nome, String referencia, UUID categoriaId, UUID fornecedorId, BigDecimal precoVenda,
            BigDecimal precoCusto, Integer estoqueMinimo, String tamTon) {
    }

    /** Produto com o custo e os códigos, pronto para a resposta da dona. */
    public record Detalhe(Produto produto, BigDecimal precoCusto, List<Codigo> codigos) {
    }

    private final RepositorioDeProdutos produtos;
    private final CustosDosProdutos custos;
    private final CodigosDosProdutos codigos;
    private final ServicoDeAuditoria auditoria;
    private final JdbcTemplate jdbc;
    private final Clock relogio;

    ServicoDeProdutos(RepositorioDeProdutos produtos, Clock relogio, CustosDosProdutos custos,
            CodigosDosProdutos codigos, ServicoDeAuditoria auditoria, JdbcTemplate jdbc) {
        super(produtos, relogio);
        this.produtos = produtos;
        this.custos = custos;
        this.codigos = codigos;
        this.auditoria = auditoria;
        this.jdbc = jdbc;
        this.relogio = relogio;
    }

    // --- leitura ---

    @Transactional(readOnly = true)
    public Page<Detalhe> listar(String busca, UUID categoriaId, UUID fornecedorId, Boolean ativo, Integer pagina,
            Integer tamanho) {
        Page<Produto> pronta = produtos.buscar(padraoDeTexto(busca), categoriaId == null ? null : categoriaId.toString(),
                fornecedorId == null ? null : fornecedorId.toString(), ativo == null ? null : ativo.toString(),
                pagina(pagina, tamanho));
        List<UUID> ids = pronta.getContent().stream().map(Produto::getId).toList();
        Map<UUID, BigDecimal> custosPorProduto = custos.dosProdutos(ids);
        Map<UUID, List<Codigo>> codigosPorProduto = codigos.dosProdutos(ids);
        List<Detalhe> itens = pronta.getContent().stream()
                .map(p -> new Detalhe(p, custosPorProduto.get(p.getId()), codigosPorProduto.getOrDefault(p.getId(), List.of())))
                .toList();
        return new PageImpl<>(itens, pronta.getPageable(), pronta.getTotalElements());
    }

    @Transactional(readOnly = true)
    public Detalhe ver(UUID id) {
        return detalhar(obter(id));
    }

    /** O produto que usa exatamente este código; sem produto, 404. */
    @Transactional(readOnly = true)
    public Detalhe porCodigo(String codigo) {
        UUID id = codigos.produtoDoCodigo(codigo).orElseThrow(ServicoDeCadastro::naoEncontrado);
        return detalhar(obter(id));
    }

    @Transactional
    public String proximoCodigoDaLoja() {
        return codigos.proximoDaLoja();
    }

    private Detalhe detalhar(Produto p) {
        return new Detalhe(p, custos.obter(p.getId()).orElse(null), codigos.doProduto(p.getId()));
    }

    // --- criar e editar ---

    @Transactional
    public Detalhe criar(UUID id, Dados d, List<Pedido> pedidosDeCodigo, UUID quem) {
        Conferidos c = conferir(d);
        List<Codigo> conferidos = codigos.conferir(pedidosDeCodigo);
        Produto novo = new Produto(Validacoes.idDoPedido(id), c.nome, c.referencia, c.categoriaId,
                c.fornecedorId, c.precoVenda, c.estoqueMinimo);
        novo.definirTamTon(c.tamTon);
        Produto produto = gravarNovo(novo);
        custos.gravar(produto.getId(), c.precoCusto);
        codigos.substituir(produto.getId(), paraPedidos(conferidos));
        Map<String, Object> depois = new LinkedHashMap<>();
        depois.put("nome", c.nome);
        depois.put("preco_venda", c.precoVenda.toPlainString());
        depois.put("preco_custo", c.precoCusto.toPlainString());
        auditoria.registrar(Ator.usuario(quem), "PRODUTO_CRIADO", "produtos", produto.getId(), null, depois);
        return detalhar(produto);
    }

    @Transactional
    public Detalhe editar(UUID id, Integer versao, Dados d, UUID quem) {
        Conferidos c = conferir(d);
        int versaoLida = Validacoes.versaoDoPedido(versao);
        Produto atual = obter(id);
        BigDecimal vendaAntes = atual.getPrecoVenda();
        BigDecimal custoAntes = custos.obter(id).orElse(null);
        Produto editado = editarComVersao(id, versaoLida, p -> {
            p.alterar(c.nome, c.referencia, c.categoriaId, c.fornecedorId, c.precoVenda, c.estoqueMinimo);
            // Sem tamTon no pedido o campo fica como está; só o texto vazio explícito o limpa (como na 9.7).
            if (d.tamTon() != null) {
                p.definirTamTon(c.tamTon);
            }
        });
        custos.gravar(id, c.precoCusto);
        if (vendaAntes.compareTo(c.precoVenda) != 0) {
            auditoria.registrar(Ator.usuario(quem), "PRECO_VENDA_ALTERADO", "produtos", id,
                    Map.of("preco_venda", vendaAntes.toPlainString()), Map.of("preco_venda", c.precoVenda.toPlainString()));
        }
        if (custoAntes == null || custoAntes.compareTo(c.precoCusto) != 0) {
            auditoria.registrar(Ator.usuario(quem), "PRECO_CUSTO_ALTERADO", "produtos", id,
                    custoAntes == null ? null : Map.of("preco_custo", custoAntes.toPlainString()),
                    Map.of("preco_custo", c.precoCusto.toPlainString()));
        }
        return detalhar(editado);
    }

    /**
     * Troca a lista de códigos com a versão do produto que a tela leu. A linha do produto é "tocada"
     * (a versão sobe e o app do vendedor vê a mudança) e fica travada até o fim, então duas pessoas
     * mexendo nos códigos da mesma peça não se atropelam.
     */
    @Transactional
    public Detalhe alterarCodigos(UUID id, Integer versao, List<Pedido> pedidos) {
        int versaoLida = Validacoes.versaoDoPedido(versao);
        int tocadas = jdbc.update("UPDATE corona.produtos SET nome = nome WHERE id = ? AND version = ? AND deleted_at IS NULL",
                id, versaoLida);
        if (tocadas == 0) {
            obter(id);
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.CADASTRO_ALTERADO);
        }
        codigos.substituir(id, pedidos);
        return detalhar(obter(id));
    }

    /** Muda só o estoque mínimo, com a versão que a tela leu. A versão sobe (o vendedor vê a mudança) e vai para a auditoria. */
    @Transactional
    public Detalhe alterarEstoqueMinimo(UUID id, Integer versao, Integer minimo, UUID quem) {
        int versaoLida = Validacoes.versaoDoPedido(versao);
        if (minimo == null || minimo < 0) {
            throw Validacoes.recusar("estoqueMinimo", "O estoque mínimo não pode ser menor que zero.");
        }
        int antes = obter(id).getEstoqueMinimo();
        Produto editado = editarComVersao(id, versaoLida, p -> p.definirEstoqueMinimo(minimo));
        auditoria.registrar(Ator.usuario(quem), "ESTOQUE_MINIMO_ALTERADO", "produtos", id, Map.of("estoque_minimo", antes),
                Map.of("estoque_minimo", minimo));
        return detalhar(editado);
    }

    @Transactional
    public Detalhe definirAtivo(UUID id, boolean ativo) {
        Produto p = obter(id);
        if (p.isAtivo() != ativo) {
            p.definirAtivo(ativo);
            gravar(p);
        }
        return detalhar(p);
    }

    /** Exclusão lógica: o produto e os códigos dele somem das listas e os códigos voltam a ficar livres. */
    @Override
    @Transactional
    public void excluir(UUID id) {
        // Peça com o vendedor não some: a devolução e o cancelamento da maleta precisam dela (tarefa 3.8).
        Boolean naMaleta = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM corona.remessas r
                                 JOIN corona.itens_remessa i ON i.remessa_id = r.id AND i.deleted_at IS NULL
                                WHERE i.produto_id = ? AND r.deleted_at IS NULL
                                  AND r.status IN ('ENVIADA', 'CONFERIDA', 'EM_ACERTO')
                                  AND i.qtd_enviada + coalesce((SELECT sum(m.quantidade) FROM corona.movimentos_remessa m
                                        WHERE m.remessa_id = i.remessa_id AND m.produto_id = i.produto_id), 0) > 0)""",
                Boolean.class, id);
        if (Boolean.TRUE.equals(naMaleta)) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.PECA_ESTA_EM_MALETA);
        }
        super.excluir(id);
        jdbc.update("UPDATE corona.produto_codigos SET deleted_at = ? WHERE produto_id = ? AND deleted_at IS NULL",
                Timestamp.from(relogio.instant()), id);
    }

    // --- conferências ---

    private record Conferidos(String nome, String referencia, UUID categoriaId, UUID fornecedorId, BigDecimal precoVenda,
            BigDecimal precoCusto, int estoqueMinimo, String tamTon) {
    }

    private Conferidos conferir(Dados d) {
        if (!existe("categorias", d.categoriaId())) {
            throw Validacoes.recusar("categoriaId", "Esta categoria não existe mais. Atualize a tela e escolha de novo.");
        }
        if (d.fornecedorId() != null && !existe("fornecedores", d.fornecedorId())) {
            throw Validacoes.recusar("fornecedorId", "Este fornecedor não existe mais. Atualize a tela e escolha de novo.");
        }
        int minimo = d.estoqueMinimo() == null ? 0 : d.estoqueMinimo();
        if (minimo < 0) {
            throw Validacoes.recusar("estoqueMinimo", "O estoque mínimo não pode ser menor que zero.");
        }
        String tamTon = Validacoes.limpar(d.tamTon());
        if (tamTon != null && tamTon.length() > 30) {
            throw Validacoes.recusar("tamTon", "Use no máximo 30 letras em Tam./Ton.");
        }
        return new Conferidos(d.nome().strip(), Validacoes.limpar(d.referencia()), d.categoriaId(), d.fornecedorId(),
                dinheiro("precoVenda", d.precoVenda()), dinheiro("precoCusto", d.precoCusto()), minimo, tamTon);
    }

    private boolean existe(String tabela, UUID id) {
        return id != null && Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM corona." + tabela + " WHERE id = ? AND deleted_at IS NULL)", Boolean.class, id));
    }

    /** Preço em reais: zero ou mais, no máximo duas casas depois da vírgula (nada é arredondado em silêncio). */
    private static BigDecimal dinheiro(String campo, BigDecimal valor) {
        if (valor == null) {
            throw Validacoes.recusar(campo, "Preencha este campo.");
        }
        if (valor.signum() < 0) {
            throw Validacoes.recusar(campo, "O valor não pode ser menor que zero.");
        }
        if (valor.stripTrailingZeros().scale() > 2) {
            throw Validacoes.recusar(campo, "Use no máximo duas casas depois da vírgula.");
        }
        if (valor.compareTo(new BigDecimal("999999999999.99")) > 0) {
            throw Validacoes.recusar(campo, "Este valor é grande demais.");
        }
        return valor.setScale(2);
    }

    private static List<Pedido> paraPedidos(List<Codigo> conferidos) {
        return conferidos.stream()
                .map(c -> new Pedido(c.id(), c.codigo(), c.tipo(), c.fornecedorId(), c.principal(), c.ativo()))
                .toList();
    }
}
