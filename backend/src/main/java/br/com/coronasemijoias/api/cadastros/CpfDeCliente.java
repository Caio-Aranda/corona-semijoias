package br.com.coronasemijoias.api.cadastros;

import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.comum.erros.Problemas;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * O CPF é único entre os clientes não excluídos (11/11/2026: clientes são da loja; ADR-0011, item 13). Quem cadastra
 * ou altera confere aqui e recebe 409 com o cliente que já existe. Dois cadastros do mesmo CPF ao mesmo tempo esperam
 * um pelo outro (trava da transação), então o segundo já enxerga o primeiro; o índice único do banco é a segunda trava.
 */
public final class CpfDeCliente {

    private CpfDeCliente() {
    }

    /** Recusa com 409 se outro cliente (diferente de {@code idDoProprio}) já tem este CPF. CPF vazio não confere nada. */
    public static void exigirLivre(JdbcTemplate jdbc, String cpf, UUID idDoProprio) {
        if (cpf == null) {
            return;
        }
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))::text", String.class, "corona.clientes.cpf:" + cpf);
        List<Map<String, Object>> achados = jdbc.queryForList("""
                SELECT id, nome, telefone, endereco_cidade FROM corona.clientes
                 WHERE cpf = ? AND deleted_at IS NULL AND id <> ? LIMIT 1""", cpf, idDoProprio == null ? new UUID(0, 0) : idDoProprio);
        if (achados.isEmpty()) {
            return;
        }
        Map<String, Object> c = achados.get(0);
        ProblemDetail corpo = Problemas.de(HttpStatus.CONFLICT, CodigoErro.CLIENTE_CPF_JA_CADASTRADO);
        Map<String, Object> existente = new LinkedHashMap<>();
        existente.put("id", c.get("id").toString());
        existente.put("nome", c.get("nome"));
        existente.put("telefone", c.get("telefone"));
        existente.put("cidade", c.get("endereco_cidade"));
        corpo.setProperty("clienteExistente", existente);
        corpo.setDetail("Este cliente já está cadastrado: " + c.get("nome")
                + ". Escolha o cliente que já existe, em vez de cadastrar de novo.");
        throw ErroDaApi.comCorpo(HttpStatus.CONFLICT, CodigoErro.CLIENTE_CPF_JA_CADASTRADO, corpo);
    }
}
