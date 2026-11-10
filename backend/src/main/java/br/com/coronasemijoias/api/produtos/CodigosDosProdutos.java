package br.com.coronasemijoias.api.produtos;

import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Códigos das etiquetas (tabela sincronizável {@code produto_codigos}; ADR-0007). Texto livre,
 * sem espaços nas pontas, único sem diferenciar maiúsculas entre os que não foram excluídos (quem
 * garante é o índice do banco; a conferência daqui só serve para dizer qual produto já usa o código).
 * O dígito verificador só vale para o tipo EAN13 com 13 dígitos.
 */
@Component
public class CodigosDosProdutos {

    static final int TAMANHO_MAXIMO = 60;
    private static final String CAMPO = "codigos";
    private static final Set<String> TIPOS = Set.of("LOJA", "FORNECEDOR", "EAN13");
    private static final int TENTATIVAS_DO_PROXIMO = 100;

    /** O que a dona mandou para um código. {@code id} só vem quando é um código que já existe. */
    record Pedido(UUID id, String codigo, String tipo, UUID fornecedorId, Boolean principal, Boolean ativo) {
    }

    /** Um código como está gravado. */
    record Codigo(UUID id, String codigo, String tipo, UUID fornecedorId, boolean principal, boolean ativo) {
    }

    private final JdbcTemplate jdbc;
    private final Clock relogio;

    CodigosDosProdutos(JdbcTemplate jdbc, Clock relogio) {
        this.jdbc = jdbc;
        this.relogio = relogio;
    }

    // --- conferência ---

    /** Confere a lista inteira e devolve os códigos já limpos, com exatamente um principal. */
    List<Codigo> conferir(List<Pedido> pedidos) {
        if (pedidos == null || pedidos.isEmpty()) {
            throw Validacoes.recusar(CAMPO, "Informe pelo menos um código para a peça.");
        }
        List<Codigo> limpos = new ArrayList<>();
        Set<String> vistos = new HashSet<>();
        int marcadosComoPrincipal = 0;
        for (Pedido p : pedidos) {
            String codigo = p.codigo() == null ? "" : p.codigo().strip();
            if (codigo.isEmpty()) {
                throw Validacoes.recusar(CAMPO, "Preencha o código da peça.");
            }
            if (codigo.length() > TAMANHO_MAXIMO) {
                throw Validacoes.recusar(CAMPO, "Use no máximo " + TAMANHO_MAXIMO + " caracteres em cada código.");
            }
            String tipo = p.tipo() == null ? "" : p.tipo().strip().toUpperCase(Locale.ROOT);
            if (!TIPOS.contains(tipo)) {
                throw Validacoes.recusar(CAMPO, "Escolha de onde vem o código: da loja, do fornecedor ou de barras.");
            }
            if ("EAN13".equals(tipo) && Ean13.temTrezeDigitos(codigo) && !Ean13.digitoConfere(codigo)) {
                throw Validacoes.recusar(CAMPO, "O código de barras " + codigo
                        + " não confere. Leia o código de novo ou confira os números.");
            }
            if (!vistos.add(codigo.toLowerCase(Locale.ROOT))) {
                throw Validacoes.recusar(CAMPO, "O código " + codigo + " aparece mais de uma vez nesta peça.");
            }
            if (p.fornecedorId() != null && !fornecedorExiste(p.fornecedorId())) {
                throw Validacoes.recusar(CAMPO, "O fornecedor deste código não existe mais. Atualize a tela e escolha de novo.");
            }
            boolean principal = Boolean.TRUE.equals(p.principal());
            boolean ativo = p.ativo() == null || p.ativo();
            if (principal) {
                marcadosComoPrincipal++;
                if (!ativo) {
                    throw Validacoes.recusar(CAMPO, "O código principal não pode estar desativado.");
                }
            }
            limpos.add(new Codigo(p.id(), codigo, tipo, p.fornecedorId(), principal, ativo));
        }
        if (marcadosComoPrincipal > 1) {
            throw Validacoes.recusar(CAMPO, "Escolha apenas um código principal.");
        }
        if (marcadosComoPrincipal == 0) {
            int primeiroAtivo = -1;
            for (int i = 0; i < limpos.size() && primeiroAtivo < 0; i++) {
                if (limpos.get(i).ativo()) {
                    primeiroAtivo = i;
                }
            }
            if (primeiroAtivo < 0) {
                throw Validacoes.recusar(CAMPO, "Deixe pelo menos um código ativo na peça.");
            }
            Codigo c = limpos.get(primeiroAtivo);
            limpos.set(primeiroAtivo, new Codigo(c.id(), c.codigo(), c.tipo(), c.fornecedorId(), true, true));
        }
        return limpos;
    }

    private boolean fornecedorExiste(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM corona.fornecedores WHERE id = ? AND deleted_at IS NULL)", Boolean.class, id));
    }

    // --- gravação ---

    /**
     * Deixa o produto com exatamente a lista pedida: o que já existe e veio com o mesmo {@code id}
     * é atualizado; o que é novo entra; o que ficou de fora é excluído (o código volta a ficar livre).
     */
    void substituir(UUID produtoId, List<Pedido> pedidos) {
        List<Codigo> novos = conferir(pedidos);
        for (Codigo c : novos) {
            exigirLivre(produtoId, c.codigo());
        }
        Map<UUID, Codigo> existentes = new LinkedHashMap<>();
        for (Codigo c : doProduto(produtoId)) {
            existentes.put(c.id(), c);
        }
        Set<UUID> mantidos = new HashSet<>();
        for (Codigo c : novos) {
            if (c.id() != null && existentes.containsKey(c.id())) {
                mantidos.add(c.id());
            }
        }
        Timestamp agora = Timestamp.from(relogio.instant());
        for (UUID id : existentes.keySet()) {
            if (!mantidos.contains(id)) {
                jdbc.update("UPDATE corona.produto_codigos SET deleted_at = ? WHERE id = ?", agora, id);
            }
        }
        try {
            // Primeiro quem deixa de ser principal, para a vaga do principal ficar livre.
            for (Codigo c : novos) {
                Codigo antigo = c.id() == null ? null : existentes.get(c.id());
                if (antigo != null && antigo.principal() && !c.principal()) {
                    atualizar(antigo, c);
                }
            }
            for (Codigo c : novos) {
                Codigo antigo = c.id() == null ? null : existentes.get(c.id());
                if (antigo != null && !(antigo.principal() && !c.principal())) {
                    atualizar(antigo, c);
                }
            }
            for (Codigo c : novos) {
                if (c.id() == null || !existentes.containsKey(c.id())) {
                    jdbc.update("""
                            INSERT INTO corona.produto_codigos (id, produto_id, codigo, tipo, fornecedor_id, principal, ativo)
                            VALUES (?, ?, ?, ?, ?, ?, ?)""", Validacoes.idDoPedido(c.id()), produtoId, c.codigo(), c.tipo(),
                            c.fornecedorId(), c.principal(), c.ativo());
                }
            }
        } catch (DataIntegrityViolationException e) {
            throw traduzir(e);
        }
    }

    private void atualizar(Codigo antigo, Codigo novo) {
        boolean igual = antigo.codigo().equals(novo.codigo()) && antigo.tipo().equals(novo.tipo())
                && java.util.Objects.equals(antigo.fornecedorId(), novo.fornecedorId())
                && antigo.principal() == novo.principal() && antigo.ativo() == novo.ativo();
        if (!igual) {
            jdbc.update("UPDATE corona.produto_codigos SET codigo = ?, tipo = ?, fornecedor_id = ?, principal = ?, ativo = ? WHERE id = ?",
                    novo.codigo(), novo.tipo(), novo.fornecedorId(), novo.principal(), novo.ativo(), antigo.id());
        }
    }

    /** Se outro produto já usa o código, diz qual. */
    private void exigirLivre(UUID produtoId, String codigo) {
        List<String> nomes = jdbc.query("""
                SELECT p.nome FROM corona.produto_codigos c JOIN corona.produtos p ON p.id = c.produto_id
                WHERE lower(c.codigo) = lower(?) AND c.deleted_at IS NULL AND c.produto_id <> ?
                LIMIT 1""", (rs, n) -> rs.getString(1), codigo, produtoId);
        if (!nomes.isEmpty()) {
            throw ErroDaApi.codigoJaUsado(codigo, nomes.get(0));
        }
    }

    private static RuntimeException traduzir(DataIntegrityViolationException e) {
        StringBuilder textos = new StringBuilder();
        for (Throwable causa = e; causa != null; causa = causa.getCause()) {
            textos.append(causa.getMessage()).append('\n');
        }
        String texto = textos.toString();
        if (texto.contains("produto_codigos_codigo_unico")) {
            // Dois pedidos ao mesmo tempo com o mesmo código: o banco deixou passar só um.
            return ErroDaApi.codigoJaUsado(null, null);
        }
        if (texto.contains("produto_codigos_pkey")) {
            return new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.CADASTRO_JA_EXISTE);
        }
        return e;
    }

    // --- leitura ---

    List<Codigo> doProduto(UUID produtoId) {
        return dosProdutos(List.of(produtoId)).getOrDefault(produtoId, List.of());
    }

    Map<UUID, List<Codigo>> dosProdutos(Collection<UUID> produtoIds) {
        Map<UUID, List<Codigo>> porProduto = new HashMap<>();
        if (produtoIds.isEmpty()) {
            return porProduto;
        }
        String marcas = String.join(",", produtoIds.stream().map(i -> "?").toList());
        jdbc.query("""
                SELECT id, produto_id, codigo, tipo, fornecedor_id, principal, ativo
                FROM corona.produto_codigos
                WHERE deleted_at IS NULL AND produto_id IN (""" + marcas + """
                )
                ORDER BY principal DESC, created_at, id""", rs -> {
                    UUID produto = rs.getObject("produto_id", UUID.class);
                    porProduto.computeIfAbsent(produto, k -> new ArrayList<>()).add(new Codigo(
                            rs.getObject("id", UUID.class), rs.getString("codigo"), rs.getString("tipo"),
                            rs.getObject("fornecedor_id", UUID.class), rs.getBoolean("principal"), rs.getBoolean("ativo")));
                }, produtoIds.toArray());
        return porProduto;
    }

    /** O produto que usa exatamente este código (sem espaços nas pontas, sem diferenciar maiúsculas). */
    public Optional<UUID> produtoDoCodigo(String digitado) {
        String codigo = digitado == null ? "" : digitado.strip();
        if (codigo.isEmpty()) {
            return Optional.empty();
        }
        return jdbc.query("""
                SELECT c.produto_id FROM corona.produto_codigos c JOIN corona.produtos p ON p.id = c.produto_id
                WHERE lower(c.codigo) = lower(?) AND c.deleted_at IS NULL AND p.deleted_at IS NULL""",
                (rs, n) -> rs.getObject(1, UUID.class), codigo).stream().findFirst();
    }

    /**
     * Próximo código da loja (peça sem etiqueta): um número sequencial do servidor. A sequência do
     * PostgreSQL nunca entrega o mesmo número a dois pedidos; números que alguém já digitou à mão
     * são pulados. Pode haver buracos.
     */
    String proximoDaLoja() {
        for (int i = 0; i < TENTATIVAS_DO_PROXIMO; i++) {
            Long numero = jdbc.queryForObject("SELECT nextval('corona.codigo_loja_seq')", Long.class);
            String codigo = String.valueOf(numero);
            Boolean emUso = jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM corona.produto_codigos WHERE lower(codigo) = lower(?) AND deleted_at IS NULL)",
                    Boolean.class, codigo);
            if (!Boolean.TRUE.equals(emUso)) {
                return codigo;
            }
        }
        throw new IllegalStateException("Não foi possível gerar um código livre da loja.");
    }
}
