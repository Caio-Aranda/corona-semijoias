package br.com.coronasemijoias.api.importacoes;

import br.com.coronasemijoias.api.cadastros.Validacoes;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Guarda o resultado das importações em massa (tarefa 1.13, §12): um lote por importação e uma linha por
 * item. Só inserção. O mesmo lote pode chegar em várias partes (o id vem do aplicativo); cada parte entra
 * depois das anteriores.
 */
@Service
public class RegistroDeImportacoes {

    public static final int LINHAS_POR_ENVIO = 500;
    static final Set<String> TIPOS = Set.of("PLANILHA", "FOTOS");
    static final Set<String> RESULTADOS = Set.of("CRIADO", "ATUALIZADO", "JA_EXISTIA", "RECUSADO");
    private static final int TAMANHO_DO_TEXTO = 500;

    /** Uma linha do lote: o que a pessoa reconhece, o que aconteceu e, se recusada, por quê. */
    public record Linha(String referencia, String resultado, String motivo) {
    }

    private final JdbcTemplate jdbc;

    public RegistroDeImportacoes(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void registrar(UUID loteId, String tipo, String arquivoNome, UUID usuarioId, List<Linha> linhas) {
        if (loteId == null) {
            throw Validacoes.recusar("id", "Este identificador não é válido. Atualize o aplicativo e tente de novo.");
        }
        String t = tipo == null ? "" : tipo.strip().toUpperCase();
        if (!TIPOS.contains(t)) {
            throw Validacoes.recusar("tipo", "Escolha planilha ou fotos.");
        }
        if (linhas == null || linhas.isEmpty()) {
            throw Validacoes.recusar("linhas", "Não há nada para registrar.");
        }
        if (linhas.size() > LINHAS_POR_ENVIO) {
            throw Validacoes.recusar("linhas", "Registre no máximo " + LINHAS_POR_ENVIO + " linhas por vez.");
        }
        for (Linha l : linhas) {
            if (l == null || l.referencia() == null || l.referencia().isBlank()) {
                throw Validacoes.recusar("linhas", "Cada linha precisa dizer de qual linha ou foto se trata.");
            }
            if (l.resultado() == null || !RESULTADOS.contains(l.resultado().strip().toUpperCase())) {
                throw Validacoes.recusar("linhas", "O resultado de cada linha deve ser criado, atualizado, já existia ou recusado.");
            }
        }
        jdbc.update("""
                INSERT INTO corona.importacoes (id, tipo, arquivo_nome, usuario_id) VALUES (?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING""", loteId, t, cortar(arquivoNome), usuarioId);
        // Trava o lote enquanto grava, para duas partes ao mesmo tempo não pegarem a mesma ordem.
        List<String> existente = jdbc.queryForList(
                "SELECT tipo || '|' || usuario_id FROM corona.importacoes WHERE id = ? FOR UPDATE", String.class, loteId);
        if (existente.isEmpty() || !existente.get(0).equals(t + "|" + usuarioId)) {
            throw Validacoes.recusar("id", "Este lote de importação já existe com outros dados. Comece uma importação nova.");
        }
        Integer proxima = jdbc.queryForObject(
                "SELECT coalesce(max(ordem) + 1, 0) FROM corona.importacoes_linhas WHERE importacao_id = ?", Integer.class,
                loteId);
        int ordem = proxima == null ? 0 : proxima;
        for (Linha l : linhas) {
            jdbc.update("""
                    INSERT INTO corona.importacoes_linhas (importacao_id, ordem, referencia, resultado, motivo)
                    VALUES (?, ?, ?, ?, ?)""", loteId, ordem++, cortar(l.referencia().strip()),
                    l.resultado().strip().toUpperCase(), l.motivo() == null || l.motivo().isBlank() ? null : cortar(l.motivo()));
        }
    }

    private static String cortar(String texto) {
        if (texto == null) {
            return null;
        }
        String limpo = texto.strip();
        return limpo.length() <= TAMANHO_DO_TEXTO ? limpo : limpo.substring(0, TAMANHO_DO_TEXTO);
    }
}
