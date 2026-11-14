package br.com.coronasemijoias.api.vendedor;

import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria;
import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria.Ator;
import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.comum.identificadores.UuidV7;
import br.com.coronasemijoias.api.comum.persistencia.OrigemDoDispositivo;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Conferência da maleta pelo vendedor (tarefa 4.3; ADR-0011, item 11). Ele conta as peças recebidas; o servidor compara
 * com o saldo da maleta naquele momento, grava a diferença (imutável, uma por maleta), liga a maleta ao aparelho e passa
 * o estado de ENVIADA para CONFERIDA. Peça que não consta na maleta é recusada: o leitor nunca revela peça de fora.
 */
@Service
public class ServicoDeConferencia {

    private static final int LIMITE_DA_QUANTIDADE = 100_000;
    private static final int LIMITE_DE_LINHAS = 500;

    public record LinhaContada(UUID produtoId, Integer contada) {
    }

    public record Resultado(UUID conferenciaId, boolean jaExistia) {
    }

    private final JdbcTemplate jdbc;
    private final ServicoDeAuditoria auditoria;
    private final OrigemDoDispositivo origem;

    ServicoDeConferencia(JdbcTemplate jdbc, ServicoDeAuditoria auditoria, OrigemDoDispositivo origem) {
        this.jdbc = jdbc;
        this.auditoria = auditoria;
        this.origem = origem;
    }

    @Transactional
    public Resultado conferir(UUID vendedorId, UUID usuarioId, UUID aparelho, UUID remessaId, UUID idDoPedido,
            List<LinhaContada> contadas) {
        UUID id = Validacoes.idDoPedido(idDoPedido);
        Map<UUID, Integer> contado = conferirLinhas(contadas);

        List<Map<String, Object>> remessa = jdbc.queryForList("""
                SELECT status, dispositivo_id FROM corona.remessas
                 WHERE id = ? AND vendedor_id = ? AND deleted_at IS NULL FOR UPDATE""", remessaId, vendedorId);
        if (remessa.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.REMESSA_NAO_ENCONTRADA);
        }
        String status = (String) remessa.get(0).get("status");
        UUID dono = (UUID) remessa.get(0).get("dispositivo_id");

        List<Map<String, Object>> feita = jdbc.queryForList(
                "SELECT id, remessa_id, vendedor_id FROM corona.conferencias WHERE remessa_id = ? OR id = ?", remessaId, id);
        if (!feita.isEmpty()) {
            Map<String, Object> f = feita.get(0);
            if (feita.size() == 1 && id.equals(f.get("id")) && remessaId.equals(f.get("remessa_id"))
                    && vendedorId.equals(f.get("vendedor_id")) && mesmaContagem(id, contado)) {
                return new Resultado(id, true);
            }
            throw new ErroDaApi(HttpStatus.CONFLICT, id.equals(f.get("id")) ? CodigoErro.LANCAMENTO_JA_REGISTRADO_DIFERENTE
                    : CodigoErro.MALETA_JA_CONFERIDA);
        }
        VendedorAtivo.exigir(jdbc, vendedorId);
        if ("EM_ACERTO".equals(status)) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.MALETA_EM_ACERTO);
        }
        if (!"ENVIADA".equals(status)) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.MALETA_NAO_ESTA_COM_VOCE);
        }
        if (dono != null && !dono.equals(aparelho)) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.MALETA_EM_OUTRO_APARELHO);
        }

        // O que devia haver: o saldo da maleta agora (enviada mais os movimentos), por peça, em ordem estável.
        List<Map<String, Object>> esperados = jdbc.queryForList("""
                SELECT i.produto_id, i.qtd_enviada + coalesce((SELECT sum(m.quantidade) FROM corona.movimentos_remessa m
                        WHERE m.remessa_id = i.remessa_id AND m.produto_id = i.produto_id), 0) AS saldo
                  FROM corona.itens_remessa i WHERE i.remessa_id = ? AND i.deleted_at IS NULL ORDER BY i.produto_id""", remessaId);
        Set<UUID> daMaleta = new HashSet<>();
        for (Map<String, Object> e : esperados) {
            daMaleta.add((UUID) e.get("produto_id"));
        }
        for (UUID produto : contado.keySet()) {
            if (!daMaleta.contains(produto)) {
                throw Validacoes.recusar("produtoId", "Esta peça não consta na sua maleta.");
            }
        }

        int divergencias = 0;
        List<Object[]> linhas = new ArrayList<>();
        for (Map<String, Object> e : esperados) {
            UUID produto = (UUID) e.get("produto_id");
            int esperado = Math.max(0, ((Number) e.get("saldo")).intValue());
            int contou = contado.getOrDefault(produto, 0);
            if (contou != esperado) {
                divergencias++;
            }
            linhas.add(new Object[] {UuidV7.gerar(), id, produto, esperado, contou});
        }
        if (aparelho != null) {
            origem.definirNaTransacaoAtual(aparelho);
        }
        jdbc.update("""
                INSERT INTO corona.conferencias (id, remessa_id, vendedor_id, dispositivo_id, divergencias)
                VALUES (?, ?, ?, ?, ?)""", id, remessaId, vendedorId, aparelho, divergencias);
        for (Object[] l : linhas) {
            jdbc.update("""
                    INSERT INTO corona.itens_conferencia (id, conferencia_id, produto_id, esperado, contado)
                    VALUES (?, ?, ?, ?, ?)""", l);
        }
        jdbc.update("UPDATE corona.remessas SET status = 'CONFERIDA', dispositivo_id = coalesce(dispositivo_id, ?) WHERE id = ?",
                aparelho, remessaId);
        Map<String, Object> depois = new LinkedHashMap<>();
        depois.put("remessa_id", remessaId.toString());
        depois.put("divergencias", divergencias);
        auditoria.registrar(Ator.usuario(usuarioId), "MALETA_CONFERIDA", "conferencias", id, null, depois);
        return new Resultado(id, false);
    }

    private static Map<UUID, Integer> conferirLinhas(List<LinhaContada> pedidas) {
        Map<UUID, Integer> contado = new LinkedHashMap<>();
        if (pedidas == null) {
            return contado;
        }
        if (pedidas.size() > LIMITE_DE_LINHAS) {
            throw Validacoes.recusar("itens", "Confira no máximo " + LIMITE_DE_LINHAS + " peças diferentes por vez.");
        }
        for (LinhaContada l : pedidas) {
            if (l == null || l.produtoId() == null) {
                throw Validacoes.recusar("produtoId", "Escolha a peça conferida.");
            }
            if (l.contada() == null || l.contada() < 0 || l.contada() > LIMITE_DA_QUANTIDADE) {
                throw Validacoes.recusar("contada", "Informe quantas peças você contou (zero ou mais).");
            }
            if (contado.put(l.produtoId(), l.contada()) != null) {
                throw Validacoes.recusar("itens", "A mesma peça aparece mais de uma vez. Junte as quantidades numa linha só.");
            }
        }
        return contado;
    }

    /** A conferência repetida é a mesma? Compara o que foi contado (peça ausente conta como zero). */
    private boolean mesmaContagem(UUID conferenciaId, Map<UUID, Integer> contado) {
        for (Map<String, Object> g : jdbc.queryForList(
                "SELECT produto_id, contado FROM corona.itens_conferencia WHERE conferencia_id = ?", conferenciaId)) {
            if (contado.getOrDefault((UUID) g.get("produto_id"), 0) != ((Number) g.get("contado")).intValue()) {
                return false;
            }
        }
        return true;
    }
}
