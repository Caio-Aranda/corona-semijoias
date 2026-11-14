package br.com.coronasemijoias.api.caixa;

import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria;
import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria.Ator;
import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * As categorias do caixa (tarefa 7.7; ADR-0012, item 11): a dona cria, renomeia, inativa e reativa as categorias dos lançamentos
 * manuais. O tipo (entrada ou saída) nunca muda; categoria do sistema (usada pelos lançamentos automáticos) não muda; não há
 * exclusão, só inativar. Nome único sem diferenciar maiúsculas nem espaços nas pontas (o banco garante). Só a dona (ADMIN).
 */
@Service
public class ServicoDeCategoriasDoCaixa {

    /** O pedido de criar: o id vem do aplicativo (idempotência). */
    public record PedidoDeCategoria(UUID id, String nome, String tipo) {
    }

    /** O pedido de renomear. */
    public record PedidoDeNome(String nome) {
    }

    /** {@code jaExistia}: a categoria já estava gravada (200); senão, foi criada agora (201). */
    public record Resultado(UUID id, boolean jaExistia) {
    }

    private static final int LIMITE_DO_NOME = 80;

    private final JdbcTemplate jdbc;
    private final ServicoDeAuditoria auditoria;

    ServicoDeCategoriasDoCaixa(JdbcTemplate jdbc, ServicoDeAuditoria auditoria) {
        this.jdbc = jdbc;
        this.auditoria = auditoria;
    }

    @Transactional
    public Resultado criar(UUID usuarioId, PedidoDeCategoria pedido) {
        if (pedido == null) {
            throw Validacoes.recusar("nome", "Informe o nome da categoria.");
        }
        UUID id = Validacoes.idDoPedido(pedido.id());
        String nome = nomeValido(pedido.nome());
        String tipo = pedido.tipo();
        if (!"ENTRADA".equals(tipo) && !"SAIDA".equals(tipo)) {
            throw Validacoes.recusar("tipo", "Escolha se a categoria é de entrada ou de saída de dinheiro.");
        }
        String resumo = resumo(nome + "|" + tipo);
        List<Map<String, Object>> existente = jdbc.queryForList("SELECT conteudo FROM corona.categorias_caixa WHERE id = ?", id);
        if (!existente.isEmpty()) {
            if (resumo.equals(existente.get(0).get("conteudo"))) {
                return new Resultado(id, true);
            }
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.CATEGORIA_DO_CAIXA_JA_EXISTE);
        }
        try {
            jdbc.update("INSERT INTO corona.categorias_caixa (id, nome, tipo, conteudo) VALUES (?, ?, ?, ?)", id, nome, tipo, resumo);
        } catch (DuplicateKeyException e) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.CATEGORIA_DO_CAIXA_JA_EXISTE);
        }
        Map<String, Object> depois = new LinkedHashMap<>();
        depois.put("nome", nome);
        depois.put("tipo", tipo);
        auditoria.registrar(Ator.usuario(usuarioId), "CATEGORIA_DO_CAIXA_CRIADA", "categorias_caixa", id, null, depois);
        return new Resultado(id, false);
    }

    @Transactional
    public void renomear(UUID usuarioId, UUID id, PedidoDeNome pedido) {
        String nome = nomeValido(pedido == null ? null : pedido.nome());
        Map<String, Object> antes = travar(id);
        try {
            jdbc.update("UPDATE corona.categorias_caixa SET nome = ? WHERE id = ?", nome, id);
        } catch (DuplicateKeyException e) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.CATEGORIA_DO_CAIXA_JA_EXISTE);
        }
        auditoria.registrar(Ator.usuario(usuarioId), "CATEGORIA_DO_CAIXA_ALTERADA", "categorias_caixa", id,
                Map.of("nome", antes.get("nome")), Map.of("nome", nome));
    }

    @Transactional
    public void inativar(UUID usuarioId, UUID id) {
        mudarAtiva(usuarioId, id, false, "CATEGORIA_DO_CAIXA_INATIVADA");
    }

    @Transactional
    public void reativar(UUID usuarioId, UUID id) {
        mudarAtiva(usuarioId, id, true, "CATEGORIA_DO_CAIXA_REATIVADA");
    }

    private void mudarAtiva(UUID usuarioId, UUID id, boolean ativa, String acao) {
        Map<String, Object> atual = travar(id);
        if (((Boolean) atual.get("ativo")) == ativa) {
            return;
        }
        jdbc.update("UPDATE corona.categorias_caixa SET ativo = ? WHERE id = ?", ativa, id);
        auditoria.registrar(Ator.usuario(usuarioId), acao, "categorias_caixa", id, Map.of("ativo", !ativa), Map.of("ativo", ativa));
    }

    /** Trava a categoria; 404 se não existe e 409 se é do sistema. */
    private Map<String, Object> travar(UUID id) {
        List<Map<String, Object>> achada = jdbc.queryForList(
                "SELECT nome, ativo, sistema FROM corona.categorias_caixa WHERE id = ? AND deleted_at IS NULL FOR UPDATE", id);
        if (achada.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.CATEGORIA_DO_CAIXA_NAO_ENCONTRADA);
        }
        if (Boolean.TRUE.equals(achada.get(0).get("sistema"))) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.CATEGORIA_DO_CAIXA_DO_SISTEMA);
        }
        return achada.get(0);
    }

    private static String nomeValido(String texto) {
        String nome = Validacoes.limpar(texto);
        if (nome == null) {
            throw Validacoes.recusar("nome", "Informe o nome da categoria.");
        }
        if (nome.length() > LIMITE_DO_NOME) {
            throw Validacoes.recusar("nome", "Use no máximo " + LIMITE_DO_NOME + " caracteres.");
        }
        return nome;
    }

    private static String resumo(String texto) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
