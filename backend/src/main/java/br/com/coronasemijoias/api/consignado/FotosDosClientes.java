package br.com.coronasemijoias.api.consignado;

import br.com.coronasemijoias.api.arquivos.ArmazenamentoDeArquivos;
import br.com.coronasemijoias.api.arquivos.TipoDeArquivo;
import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.comum.identificadores.UuidV7;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * As fotos do cliente do consignado (ADR-0016, item 9): a foto do cliente e a frente e o verso do documento. São ARQUIVOS
 * (nunca binário no PostgreSQL), guardados pelo mesmo {@link ArmazenamentoDeArquivos} das fotos de produto, na pasta
 * {@code clientes}; o cadastro guarda só o hash SHA-256. O envio do arquivo vai à parte do cadastro (a falta de envio nunca
 * trava a nota): o servidor confere o hash contra o do cadastro; repetir é inofensivo. Nunca vão no pull.
 */
@Service
public class FotosDosClientes {

    /** O tipo na rota e a coluna do cadastro que guarda o hash. */
    public enum TipoDeFoto {
        CLIENTE("cliente", "foto_hash"), DOCUMENTO_FRENTE("documento-frente", "foto_documento_frente_hash"),
        DOCUMENTO_VERSO("documento-verso", "foto_documento_verso_hash");

        private final String rota;
        private final String coluna;

        TipoDeFoto(String rota, String coluna) {
            this.rota = rota;
            this.coluna = coluna;
        }

        public static TipoDeFoto daRota(String rota) {
            for (TipoDeFoto t : values()) {
                if (t.rota.equals(rota)) {
                    return t;
                }
            }
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.FOTO_DO_CLIENTE_NAO_ENCONTRADA);
        }
    }

    public record Foto(byte[] conteudo, String tipoDeConteudo, String hash) {
    }

    private static final int MAXIMO_DE_BYTES = 10 * 1024 * 1024;

    private final JdbcTemplate jdbc;
    private final ArmazenamentoDeArquivos armazenamento;

    public FotosDosClientes(JdbcTemplate jdbc, ArmazenamentoDeArquivos armazenamento) {
        this.jdbc = jdbc;
        this.armazenamento = armazenamento;
    }

    /** @return {@code true} se a foto já estava guardada (repetir é inofensivo) */
    @Transactional
    public boolean guardar(UUID vendedorId, UUID clienteId, TipoDeFoto tipo, byte[] conteudo) {
        if (conteudo == null || conteudo.length == 0 || conteudo.length > MAXIMO_DE_BYTES) {
            throw Validacoes.recusar("foto", "A foto está vazia ou grande demais. Tire a foto de novo.");
        }
        TipoDeArquivo formato = TipoDeArquivo.identificar(conteudo).filter(t -> t != TipoDeArquivo.PDF)
                .orElseThrow(() -> Validacoes.recusar("foto", "Envie a foto em JPG, PNG ou WebP."));
        List<Map<String, Object>> cliente = jdbc.queryForList(
                "SELECT vendedor_responsavel_id, " + tipo.coluna + " AS hash FROM corona.clientes WHERE id = ? AND deleted_at IS NULL", clienteId);
        if (cliente.isEmpty()) {
            // O cadastro ainda não chegou ao servidor: o computador tenta de novo mais tarde.
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.CLIENTE_AINDA_NAO_CHEGOU);
        }
        if (!vendedorId.equals(cliente.get(0).get("vendedor_responsavel_id"))) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.CADASTRO_NAO_ENCONTRADO);
        }
        String hash = sha256(conteudo);
        if (!hash.equals(cliente.get(0).get("hash"))) {
            throw new ErroDaApi(HttpStatus.UNPROCESSABLE_ENTITY, CodigoErro.FOTO_DIFERENTE_DO_CADASTRO);
        }
        Boolean ja = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM corona.fotos_de_cliente WHERE cliente_id = ? AND hash = ?)",
                Boolean.class, clienteId, hash);
        if (Boolean.TRUE.equals(ja)) {
            return true;
        }
        String chave = armazenamento.guardar("clientes", conteudo, formato.extensao());
        jdbc.update("""
                INSERT INTO corona.fotos_de_cliente (id, cliente_id, tipo, hash, chave, tipo_de_conteudo, tamanho_bytes)
                VALUES (?, ?, ?, ?, ?, ?, ?)""", UuidV7.gerar(), clienteId, tipo.name(), hash, chave, formato.tipoDeConteudo(),
                conteudo.length);
        return false;
    }

    /** A foto atual do cadastro; do representante dono ({@code vendedorId}) ou, com nulo, da dona. */
    @Transactional(readOnly = true)
    public Foto ler(UUID vendedorId, UUID clienteId, TipoDeFoto tipo) {
        List<Map<String, Object>> cliente = jdbc.queryForList(
                "SELECT vendedor_responsavel_id, " + tipo.coluna + " AS hash FROM corona.clientes WHERE id = ? AND deleted_at IS NULL", clienteId);
        if (cliente.isEmpty() || (vendedorId != null && !vendedorId.equals(cliente.get(0).get("vendedor_responsavel_id")))
                || cliente.get(0).get("hash") == null) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.FOTO_DO_CLIENTE_NAO_ENCONTRADA);
        }
        String hash = (String) cliente.get(0).get("hash");
        List<Map<String, Object>> arquivo = jdbc.queryForList(
                "SELECT chave, tipo_de_conteudo FROM corona.fotos_de_cliente WHERE cliente_id = ? AND hash = ?", clienteId, hash);
        if (arquivo.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.FOTO_DO_CLIENTE_NAO_ENCONTRADA);
        }
        return new Foto(armazenamento.ler((String) arquivo.get(0).get("chave")), (String) arquivo.get(0).get("tipo_de_conteudo"), hash);
    }

    static String sha256(byte[] conteudo) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(conteudo));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Só para os testes: o hash de um texto como se fosse uma foto. */
    static String hashDe(String texto) {
        return sha256(texto.getBytes(StandardCharsets.UTF_8));
    }
}
