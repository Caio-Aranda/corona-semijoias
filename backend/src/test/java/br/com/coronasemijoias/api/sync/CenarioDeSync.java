package br.com.coronasemijoias.api.sync;

import br.com.coronasemijoias.api.estoque.ServicoDeEstoque;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.OrigemDoMovimento;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.Pedido;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.TipoDeMovimento;
import br.com.coronasemijoias.api.remessas.ServicoDeRemessas;
import br.com.coronasemijoias.api.remessas.ServicoDeRemessas.PedidoDeRascunho;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas;
import br.com.coronasemijoias.api.vendedor.ServicoDeConferencia;
import br.com.coronasemijoias.api.vendedor.ServicoDeConferencia.LinhaContada;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Objects;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Apoio dos testes do pull do vendedor (tarefa 5.4): monta peças, maletas, vendas e clientes pelos serviços reais (ou
 * por SQL, quando é só dado da dona), e guarda a "cópia local" de um vendedor aplicando a regra da {@code version}
 * (ADR-0013, item 3) como o app fará.
 */
final class CenarioDeSync {

    private static final AtomicInteger SEMANAS = new AtomicInteger(1);

    final JdbcTemplate jdbc;
    final UsuariosDeTeste usuarios;
    final ServicoDeEstoque estoque;
    final ServicoDeRemessas remessas;
    final ServicoDeConferencia conferencia;
    final ServicoDeVendas vendas;
    final UUID categoria;

    CenarioDeSync(JdbcTemplate jdbc, UsuariosDeTeste usuarios, ServicoDeEstoque estoque, ServicoDeRemessas remessas,
            ServicoDeConferencia conferencia, ServicoDeVendas vendas) {
        this.jdbc = jdbc;
        this.usuarios = usuarios;
        this.estoque = estoque;
        this.remessas = remessas;
        this.conferencia = conferencia;
        this.vendas = vendas;
        this.categoria = UUID.randomUUID();
        jdbc.update("INSERT INTO corona.categorias (id, nome) VALUES (?, ?)", categoria, "Categoria " + categoria);
    }

    UUID dona() {
        return usuarios.dona().id();
    }

    /** Peça com código, uma foto e estoque na loja (dado da dona, gravado direto). */
    UUID peca(BigDecimal preco, int estoqueDaLoja) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO corona.produtos (id, nome, categoria_id, preco_venda) VALUES (?, ?, ?, ?)", id,
                "Peça " + id, categoria, preco);
        jdbc.update("INSERT INTO corona.produto_codigos (id, produto_id, codigo, tipo, principal) VALUES (?, ?, ?, 'LOJA', true)",
                UUID.randomUUID(), id, "S-" + id);
        trocarFoto(id);
        if (estoqueDaLoja > 0) {
            estoque.registrar(new Pedido(null, id, TipoDeMovimento.ENTRADA, estoqueDaLoja, null, OrigemDoMovimento.MANUAL, null),
                    dona());
        }
        return id;
    }

    void mudarPreco(UUID produto) {
        jdbc.update("UPDATE corona.produtos SET preco_venda = preco_venda + 1 WHERE id = ?", produto);
    }

    void acrescentarCodigo(UUID produto) {
        jdbc.update("INSERT INTO corona.produto_codigos (id, produto_id, codigo, tipo, principal) VALUES (?, ?, ?, 'FORNECEDOR', false)",
                UUID.randomUUID(), produto, "F-" + UUID.randomUUID());
    }

    void excluirUmCodigoSecundario(UUID produto) {
        jdbc.update("""
                UPDATE corona.produto_codigos SET deleted_at = now()
                 WHERE id = (SELECT id FROM corona.produto_codigos WHERE produto_id = ? AND NOT principal AND deleted_at IS NULL LIMIT 1)""",
                produto);
    }

    /** A foto antiga sai (lápide) e entra outra (o hash muda). */
    void trocarFoto(UUID produto) {
        jdbc.update("UPDATE corona.produtos_fotos SET deleted_at = now(), principal = false WHERE produto_id = ? AND deleted_at IS NULL",
                produto);
        byte[] bytes = new byte[32];
        new Random().nextBytes(bytes);
        String hash = HexFormat.of().formatHex(bytes);
        jdbc.update("""
                INSERT INTO corona.produtos_fotos (id, produto_id, arquivo_hash, principal, ordem, largura, altura, tamanho_bytes,
                    chave_miniatura, chave_media, chave_grande)
                VALUES (?, ?, ?, true, 0, 10, 10, 100, 'm', 'md', 'g')""", UUID.randomUUID(), produto, hash);
        jdbc.update("UPDATE corona.produtos SET foto_principal_hash = ? WHERE id = ?", hash, produto);
    }

    /** Semana nova, sem sobrepor nenhuma outra (o banco recusa semanas sobrepostas). Uma por chamada, na mesma JVM. */
    UUID semana() {
        int n = SEMANAS.getAndIncrement();
        UUID id = UUID.randomUUID();
        LocalDate inicio = LocalDate.of(2700, 1, 4).plusDays(7L * n);
        jdbc.update("INSERT INTO corona.semanas (id, numero, inicio, fim) VALUES (?, ?, ?, ?)", id, 300_000 + n, inicio,
                inicio.plusDays(6));
        return id;
    }

    /** Rascunho de maleta com as peças (produto → quantidade). */
    UUID rascunho(UsuarioDeTeste vendedor, Map<UUID, Integer> pecas) {
        UUID id = UUID.randomUUID();
        remessas.criarRascunho(new PedidoDeRascunho(id, vendedor.vendedorId(), semana(), null, "NOVA"), dona());
        pecas.forEach((p, q) -> remessas.colocarItem(id, p, q, versao(id), dona()));
        return id;
    }

    private int versao(UUID remessa) {
        return jdbc.queryForObject("SELECT version FROM corona.remessas WHERE id = ?", Integer.class, remessa);
    }

    void excluirRascunho(UUID rascunho) {
        remessas.excluirRascunho(rascunho, versao(rascunho), dona());
    }

    void enviar(UUID rascunho) {
        remessas.enviar(rascunho, dona());
    }

    UUID maleta(UsuarioDeTeste vendedor, Map<UUID, Integer> pecas) {
        UUID id = rascunho(vendedor, pecas);
        remessas.enviar(id, dona());
        return id;
    }

    /** Confere a maleta com tudo certo (contou o que devia haver). */
    void conferir(UsuarioDeTeste vendedor, UUID maleta) {
        List<LinhaContada> contadas = new ArrayList<>();
        for (Map<String, Object> l : jdbc.queryForList("""
                SELECT i.produto_id, i.qtd_enviada + coalesce((SELECT sum(m.quantidade) FROM corona.movimentos_remessa m
                       WHERE m.remessa_id = i.remessa_id AND m.produto_id = i.produto_id), 0) AS saldo
                  FROM corona.itens_remessa i WHERE i.remessa_id = ? AND i.deleted_at IS NULL""", maleta)) {
            contadas.add(new LinhaContada((UUID) l.get("produto_id"), ((Number) l.get("saldo")).intValue()));
        }
        conferencia.conferir(vendedor.vendedorId(), vendedor.id(), usuarios.aparelhoDe(vendedor), maleta, UUID.randomUUID(),
                contadas);
    }

    /** Vende uma peça da maleta (já conferida) ao cliente, à vista em dinheiro. */
    void vender(UsuarioDeTeste vendedor, UUID maleta, UUID cliente, UUID produto) {
        vendas.vender(vendedor.vendedorId(), vendedor.id(), usuarios.aparelhoDe(vendedor),
                new ServicoDeVendas.PedidoDeVenda(UUID.randomUUID(), maleta, cliente,
                        List.of(new ServicoDeVendas.LinhaDoPedido(produto, 1, null)), List.of(), null));
    }

    UUID cliente(UUID vendedorResponsavel) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO corona.clientes (id, nome, telefone, whatsapp, observacoes, endereco_cidade, vendedor_responsavel_id)
                VALUES (?, ?, '(19) 3000-0001', '(19) 3000-0002', 'Só paga em dinheiro', 'Campinas', ?)""",
                id, "Cliente " + id, vendedorResponsavel);
        return id;
    }

    void transferir(UUID cliente, UUID novoVendedor) {
        jdbc.update("UPDATE corona.clientes SET vendedor_responsavel_id = ? WHERE id = ?", novoVendedor, cliente);
    }

    /** Saldo da peça na maleta (enviada + movimentos). */
    int saldo(UUID maleta, UUID produto) {
        Integer s = jdbc.queryForObject("""
                SELECT i.qtd_enviada + coalesce((SELECT sum(m.quantidade) FROM corona.movimentos_remessa m
                       WHERE m.remessa_id = i.remessa_id AND m.produto_id = i.produto_id), 0)
                  FROM corona.itens_remessa i WHERE i.remessa_id = ? AND i.produto_id = ? AND i.deleted_at IS NULL""",
                Integer.class, maleta, produto);
        return s == null ? 0 : s;
    }

    // --- a cópia local de um vendedor ---

    /** Uma linha guardada no "computador": versão, lápide e as colunas recebidas. */
    record Guardada(int versao, boolean excluida, Map<String, Object> colunas) {
    }

    /** A cópia local: tabela → id → linha. Aplica só se a {@code version} recebida for maior (ADR-0013, item 3). */
    static final class CopiaLocal {

        final Map<String, Map<UUID, Guardada>> tabelas = new HashMap<>();
        String cursor;

        void aplicar(LinhaDoPull linha) {
            Map<UUID, Guardada> tabela = tabelas.computeIfAbsent(linha.tabela(), t -> new HashMap<>());
            Guardada atual = tabela.get(linha.id());
            if (atual == null || linha.versao() > atual.versao()) {
                tabela.put(linha.id(), new Guardada(linha.versao(), linha.excluidaEm() != null, linha.colunas()));
            }
        }

        void aplicar(LoteDoPull lote) {
            lote.linhas().forEach(this::aplicar);
            cursor = lote.proximoCursor();
        }

        /** Puxa até não haver mais nada, com o lote pedido. */
        void puxarTudo(ServicoDePull pull, UUID vendedorId, int lote) {
            LoteDoPull l;
            do {
                l = pull.puxar(cursor, lote, new FiltroPorVendedor(vendedorId));
                aplicar(l);
            } while (l.haMais());
        }

        Map<String, Map<UUID, Integer>> versoes() {
            Map<String, Map<UUID, Integer>> v = new HashMap<>();
            tabelas.forEach((t, linhas) -> {
                Map<UUID, Integer> m = new HashMap<>();
                linhas.forEach((id, g) -> m.put(id, g.versao()));
                v.put(t, m);
            });
            return v;
        }

        Set<UUID> ids(String tabela) {
            return tabelas.getOrDefault(tabela, Map.of()).keySet();
        }
    }

    /** Versão e lápide: o que o oráculo compara. */
    record Linha(int versao, boolean excluida) {
    }

    /**
     * O oráculo: o que é do vendedor V, calculado à parte, lendo as tabelas inteiras e aplicando as regras do ADR-0013
     * (item 4 e linha de 11/11/2026) em Java. Não usa o {@link ServicoDePull} nem o {@link FiltroPorVendedor}.
     */
    static Map<String, Map<UUID, Linha>> oraculo(JdbcTemplate jdbc, UUID v) {
        Map<String, List<Map<String, Object>>> tudo = new HashMap<>();
        for (String t : List.of("cotacoes", "categorias", "semanas", "clientes", "produtos", "produto_codigos",
                "produtos_fotos", "remessas", "itens_remessa", "movimentos_remessa", "vendas", "itens_venda",
                "pagamentos_cliente", "conferencias", "itens_conferencia")) {
            tudo.put(t, jdbc.queryForList("SELECT * FROM corona." + t));
        }
        Map<String, Map<UUID, Linha>> r = new HashMap<>();
        for (String daLoja : List.of("cotacoes", "categorias", "semanas", "clientes")) {
            r.put(daLoja, linhas(tudo.get(daLoja), l -> true));
        }
        Set<Object> maletas = ids(tudo.get("remessas"), l -> v.equals(l.get("vendedor_id")) && l.get("enviada_em") != null);
        r.put("remessas", linhas(tudo.get("remessas"), l -> maletas.contains(l.get("id"))));
        r.put("itens_remessa", linhas(tudo.get("itens_remessa"), l -> maletas.contains(l.get("remessa_id"))));
        r.put("movimentos_remessa", linhas(tudo.get("movimentos_remessa"), l -> maletas.contains(l.get("remessa_id"))));
        Set<Object> produtos = new HashSet<>();
        for (Map<String, Object> item : tudo.get("itens_remessa")) {
            if (maletas.contains(item.get("remessa_id")) && item.get("deleted_at") == null) {
                produtos.add(item.get("produto_id"));
            }
        }
        r.put("produtos", linhas(tudo.get("produtos"), l -> produtos.contains(l.get("id"))));
        r.put("produto_codigos", linhas(tudo.get("produto_codigos"), l -> produtos.contains(l.get("produto_id"))));
        r.put("produtos_fotos", linhas(tudo.get("produtos_fotos"), l -> produtos.contains(l.get("produto_id"))));
        Set<Object> minhasVendas = ids(tudo.get("vendas"), l -> v.equals(l.get("vendedor_id")));
        r.put("vendas", linhas(tudo.get("vendas"), l -> minhasVendas.contains(l.get("id"))));
        r.put("itens_venda", linhas(tudo.get("itens_venda"), l -> minhasVendas.contains(l.get("venda_id"))));
        r.put("pagamentos_cliente", linhas(tudo.get("pagamentos_cliente"), l -> minhasVendas.contains(l.get("venda_id"))));
        Set<Object> minhasConferencias = ids(tudo.get("conferencias"), l -> v.equals(l.get("vendedor_id")));
        r.put("conferencias", linhas(tudo.get("conferencias"), l -> minhasConferencias.contains(l.get("id"))));
        r.put("itens_conferencia", linhas(tudo.get("itens_conferencia"),
                l -> minhasConferencias.contains(l.get("conferencia_id"))));
        r.values().removeIf(Map::isEmpty);
        return r;
    }

    private static Set<Object> ids(List<Map<String, Object>> linhas, java.util.function.Predicate<Map<String, Object>> filtro) {
        Set<Object> ids = new HashSet<>();
        linhas.stream().filter(filtro).forEach(l -> ids.add(l.get("id")));
        return ids;
    }

    private static Map<UUID, Linha> linhas(List<Map<String, Object>> linhas,
            java.util.function.Predicate<Map<String, Object>> filtro) {
        Map<UUID, Linha> r = new HashMap<>();
        linhas.stream().filter(filtro).forEach(l -> r.put((UUID) l.get("id"),
                new Linha(((Number) l.get("version")).intValue(), Objects.nonNull(l.get("deleted_at")))));
        return r;
    }

    /** A cópia local nas mesmas medidas do oráculo (versão e lápide). */
    static Map<String, Map<UUID, Linha>> medidas(CopiaLocal copia) {
        Map<String, Map<UUID, Linha>> r = new HashMap<>();
        copia.tabelas.forEach((t, linhas) -> {
            Map<UUID, Linha> m = new HashMap<>();
            linhas.forEach((id, g) -> m.put(id, new Linha(g.versao(), g.excluida())));
            if (!m.isEmpty()) {
                r.put(t, m);
            }
        });
        return r;
    }
}
