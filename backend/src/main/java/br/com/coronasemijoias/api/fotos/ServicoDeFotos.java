package br.com.coronasemijoias.api.fotos;

import br.com.coronasemijoias.api.arquivos.ArmazenamentoDeArquivos;
import br.com.coronasemijoias.api.arquivos.ArquivoNaoEncontradoException;
import br.com.coronasemijoias.api.arquivos.PropriedadesDeArquivos;
import br.com.coronasemijoias.api.cadastros.ServicoDeCadastro;
import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.comum.identificadores.UuidV7;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fotos do produto (§20). Cada foto vira três arquivos JPEG (miniatura, média e grande) guardados pelo
 * {@link ArmazenamentoDeArquivos}; o banco guarda só o hash, a ordem e as chaves. Até 5 fotos por peça,
 * uma principal. Todas as mudanças de uma peça travam a linha do produto, então duas pessoas (ou dois
 * envios ao mesmo tempo) não passam de 5 fotos nem criam duas principais. O produto ganha uma cópia do
 * hash da foto principal ({@code foto_principal_hash}), que o aplicativo do vendedor recebe no sync.
 */
@Service
public class ServicoDeFotos {

    public static final int MAXIMO_POR_PECA = 5;
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    /** Os três tamanhos de uma foto. */
    public enum Tamanho {
        MINIATURA("chave_miniatura"), MEDIA("chave_media"), GRANDE("chave_grande");

        private final String coluna;

        Tamanho(String coluna) {
            this.coluna = coluna;
        }

        static Optional<Tamanho> de(String texto) {
            for (Tamanho t : values()) {
                if (t.name().equalsIgnoreCase(texto)) {
                    return Optional.of(t);
                }
            }
            return Optional.empty();
        }
    }

    /** Uma foto como está gravada (sem as chaves dos arquivos, que não saem do servidor). */
    public record Foto(UUID id, UUID produtoId, String hash, boolean principal, int ordem, int largura, int altura,
            int tamanhoBytes, int version) {
    }

    /** Resultado de um envio: {@code nova} é falso quando a peça já tinha exatamente esta foto. */
    public record Enviada(Foto foto, boolean nova) {
    }

    /** O conteúdo de um tamanho, pronto para o download. */
    public record Baixada(byte[] conteudo, String hash, Tamanho tamanho) {
    }

    private final ArmazenamentoDeArquivos armazenamento;
    private final PropriedadesDeArquivos arquivos;
    private final ProcessadorDeImagem processador;
    private final TransactionTemplate transacao;
    private final JdbcTemplate jdbc;
    private final Clock relogio;

    public ServicoDeFotos(ArmazenamentoDeArquivos armazenamento, PropriedadesDeArquivos arquivos,
            PropriedadesDeFotos fotos, TransactionTemplate transacao, JdbcTemplate jdbc, Clock relogio) {
        this.armazenamento = armazenamento;
        this.arquivos = arquivos;
        this.processador = new ProcessadorDeImagem(fotos.maximoDePontos());
        this.transacao = transacao;
        this.jdbc = jdbc;
        this.relogio = relogio;
    }

    // --- envio ---

    /**
     * Confere, recodifica e grava a foto. O trabalho pesado (abrir e reduzir a imagem) acontece antes de
     * travar a peça; a gravação do banco é uma transação curta.
     */
    public Enviada adicionar(UUID produtoId, byte[] original) {
        if (original == null || original.length == 0) {
            throw Validacoes.recusar("arquivo", "Este arquivo está vazio. Escolha outro.");
        }
        if (original.length > arquivos.tamanhoMaximo().toBytes()) {
            throw new ErroDaApi(HttpStatus.PAYLOAD_TOO_LARGE, CodigoErro.ARQUIVO_GRANDE_DEMAIS);
        }
        exigirProduto(produtoId);
        ProcessadorDeImagem.Processada pronta = processador.processar(original);
        try {
            return transacao.execute(status -> gravar(produtoId, pronta));
        } catch (DataIntegrityViolationException e) {
            // Dois envios da mesma foto ao mesmo tempo: o banco deixou passar um só; o outro vê a foto já gravada.
            return transacao.execute(status -> jaGravada(produtoId, pronta.hash())
                    .map(f -> new Enviada(f, false)).orElseThrow(() -> e));
        }
    }

    private Enviada gravar(UUID produtoId, ProcessadorDeImagem.Processada pronta) {
        travar(produtoId);
        Optional<Foto> existente = jaGravada(produtoId, pronta.hash());
        if (existente.isPresent()) {
            return new Enviada(existente.get(), false);
        }
        Integer ativas = jdbc.queryForObject(
                "SELECT count(*) FROM corona.produtos_fotos WHERE produto_id = ? AND deleted_at IS NULL", Integer.class, produtoId);
        if (ativas != null && ativas >= MAXIMO_POR_PECA) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.PRODUTO_COM_FOTOS_DEMAIS);
        }
        // Os arquivos de uma foto que já existe em outra peça são reaproveitados (mesmo hash = mesmo conteúdo).
        List<String[]> chaves = jdbc.query("""
                SELECT chave_miniatura, chave_media, chave_grande FROM corona.produtos_fotos
                WHERE arquivo_hash = ? ORDER BY created_at LIMIT 1""",
                (rs, n) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3)}, pronta.hash());
        String[] arquivosDaFoto = chaves.isEmpty()
                ? new String[] {guardar(pronta.miniatura()), guardar(pronta.media()), guardar(pronta.grande())}
                : chaves.get(0);
        int tamanho = pronta.miniatura().length + pronta.media().length + pronta.grande().length;
        Integer proximaOrdem = jdbc.queryForObject(
                "SELECT coalesce(max(ordem) + 1, 0) FROM corona.produtos_fotos WHERE produto_id = ? AND deleted_at IS NULL",
                Integer.class, produtoId);
        boolean principal = ativas == null || ativas == 0;
        UUID id = UuidV7.gerar();
        jdbc.update("""
                INSERT INTO corona.produtos_fotos (id, produto_id, arquivo_hash, principal, ordem, largura, altura,
                    tamanho_bytes, chave_miniatura, chave_media, chave_grande)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, produtoId, pronta.hash(), principal, proximaOrdem,
                pronta.largura(), pronta.altura(), tamanho, arquivosDaFoto[0], arquivosDaFoto[1], arquivosDaFoto[2]);
        if (principal) {
            copiarPrincipalParaOProduto(produtoId, pronta.hash());
        }
        return new Enviada(procurar(produtoId, id), true);
    }

    private String guardar(byte[] conteudo) {
        return armazenamento.guardar("fotos", conteudo, "jpg");
    }

    private Optional<Foto> jaGravada(UUID produtoId, String hash) {
        return jdbc.query(SELECIONAR_FOTO + " WHERE produto_id = ? AND arquivo_hash = ? AND deleted_at IS NULL",
                (rs, n) -> foto(rs), produtoId, hash).stream().findFirst();
    }

    // --- leitura ---

    public List<Foto> listar(UUID produtoId) {
        exigirProduto(produtoId);
        return fotosDe(produtoId);
    }

    private List<Foto> fotosDe(UUID produtoId) {
        return jdbc.query(SELECIONAR_FOTO + " WHERE produto_id = ? AND deleted_at IS NULL ORDER BY ordem, created_at, id",
                (rs, n) -> foto(rs), produtoId);
    }

    private Foto procurar(UUID produtoId, UUID fotoId) {
        return jdbc.query(SELECIONAR_FOTO + " WHERE id = ? AND produto_id = ? AND deleted_at IS NULL", (rs, n) -> foto(rs),
                fotoId, produtoId).stream().findFirst().orElseThrow(ServicoDeCadastro::naoEncontrado);
    }

    /** O arquivo de um tamanho; 404 se não há foto ativa com este hash (excluída, trocada ou inventada). */
    public Baixada baixar(String hash, String tamanhoPedido) {
        Tamanho tamanho = Tamanho.de(tamanhoPedido)
                .orElseThrow(ServicoDeCadastro::naoEncontrado);
        if (hash == null || !HASH.matcher(hash).matches()) {
            throw ServicoDeCadastro.naoEncontrado();
        }
        List<String> chaves = jdbc.query("""
                SELECT f.%s FROM corona.produtos_fotos f JOIN corona.produtos p ON p.id = f.produto_id
                WHERE f.arquivo_hash = ? AND f.deleted_at IS NULL AND p.deleted_at IS NULL LIMIT 1""".formatted(tamanho.coluna),
                (rs, n) -> rs.getString(1), hash);
        if (chaves.isEmpty()) {
            throw ServicoDeCadastro.naoEncontrado();
        }
        try {
            return new Baixada(armazenamento.ler(chaves.get(0)), hash, tamanho);
        } catch (ArquivoNaoEncontradoException e) {
            // O arquivo sumiu do armazenamento: para quem pede é como se não houvesse foto (nunca trava a venda).
            throw ServicoDeCadastro.naoEncontrado();
        }
    }

    // --- principal, ordem e exclusão ---

    public List<Foto> definirPrincipal(UUID produtoId, UUID fotoId) {
        return transacao.execute(status -> {
            travar(produtoId);
            Foto alvo = procurar(produtoId, fotoId);
            if (!alvo.principal()) {
                jdbc.update("UPDATE corona.produtos_fotos SET principal = false WHERE produto_id = ? AND principal AND deleted_at IS NULL",
                        produtoId);
                jdbc.update("UPDATE corona.produtos_fotos SET principal = true WHERE id = ?", fotoId);
                copiarPrincipalParaOProduto(produtoId, alvo.hash());
            }
            return fotosDe(produtoId);
        });
    }

    /** A lista precisa ter exatamente as fotos da peça, cada uma uma vez, na ordem nova. */
    public List<Foto> reordenar(UUID produtoId, List<UUID> ids) {
        return transacao.execute(status -> {
            travar(produtoId);
            List<Foto> atuais = fotosDe(produtoId);
            Set<UUID> esperados = new HashSet<>();
            atuais.forEach(f -> esperados.add(f.id()));
            if (ids == null || ids.size() != esperados.size() || !esperados.equals(new HashSet<>(ids))) {
                throw Validacoes.recusar("ids", "A lista de fotos não confere com a peça. Atualize a tela e tente de novo.");
            }
            for (int i = 0; i < ids.size(); i++) {
                jdbc.update("UPDATE corona.produtos_fotos SET ordem = ? WHERE id = ? AND ordem <> ?", i, ids.get(i), i);
            }
            return fotosDe(produtoId);
        });
    }

    /** Exclusão lógica: a foto some da lista e do download, mas os arquivos ficam guardados. */
    public void excluir(UUID produtoId, UUID fotoId) {
        transacao.executeWithoutResult(status -> {
            travar(produtoId);
            Foto alvo = procurar(produtoId, fotoId);
            jdbc.update("UPDATE corona.produtos_fotos SET deleted_at = ?, principal = false WHERE id = ?",
                    Timestamp.from(relogio.instant()), fotoId);
            List<Foto> restantes = fotosDe(produtoId);
            for (int i = 0; i < restantes.size(); i++) {
                jdbc.update("UPDATE corona.produtos_fotos SET ordem = ? WHERE id = ? AND ordem <> ?", i, restantes.get(i).id(), i);
            }
            if (alvo.principal()) {
                if (restantes.isEmpty()) {
                    copiarPrincipalParaOProduto(produtoId, null);
                } else {
                    jdbc.update("UPDATE corona.produtos_fotos SET principal = true WHERE id = ?", restantes.get(0).id());
                    copiarPrincipalParaOProduto(produtoId, restantes.get(0).hash());
                }
            }
        });
    }

    // --- apoio ---

    private void exigirProduto(UUID produtoId) {
        Boolean existe = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM corona.produtos WHERE id = ? AND deleted_at IS NULL)", Boolean.class, produtoId);
        if (!Boolean.TRUE.equals(existe)) {
            throw ServicoDeCadastro.naoEncontrado();
        }
    }

    /** Trava a linha do produto até o fim da transação (e dá 404 se ele não existe ou foi excluído). */
    private void travar(UUID produtoId) {
        List<UUID> achados = jdbc.query("SELECT id FROM corona.produtos WHERE id = ? AND deleted_at IS NULL FOR UPDATE",
                (rs, n) -> rs.getObject(1, UUID.class), produtoId);
        if (achados.isEmpty()) {
            throw ServicoDeCadastro.naoEncontrado();
        }
    }

    /** O produto leva o hash da foto principal; só mexe (e só sobe a versão) quando o hash muda. */
    private void copiarPrincipalParaOProduto(UUID produtoId, String hash) {
        jdbc.update("UPDATE corona.produtos SET foto_principal_hash = ? WHERE id = ? AND foto_principal_hash IS DISTINCT FROM ?",
                hash, produtoId, hash);
    }

    private static final String SELECIONAR_FOTO = """
            SELECT id, produto_id, arquivo_hash, principal, ordem, largura, altura, tamanho_bytes, version
            FROM corona.produtos_fotos""";

    private static Foto foto(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Foto(rs.getObject("id", UUID.class), rs.getObject("produto_id", UUID.class), rs.getString("arquivo_hash"),
                rs.getBoolean("principal"), rs.getInt("ordem"), rs.getInt("largura"), rs.getInt("altura"),
                rs.getInt("tamanho_bytes"), rs.getInt("version"));
    }
}
