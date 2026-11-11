package br.com.coronasemijoias.api.vendedor;

import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria;
import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria.Ator;
import br.com.coronasemijoias.api.cadastros.CpfDeCliente;
import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.comum.persistencia.OrigemDoDispositivo;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Clientes da loja vistos pelo vendedor (tarefa 4.3; ADR-0011, item 13, com a mudança de 11/11/2026): cadastro, busca e
 * leitura de TODOS os clientes ativos, só com as colunas do diretório (nome, CPF, telefone e cidade). Os campos a mais
 * (nascimento, WhatsApp e observações) só vêm dos clientes em que ele é o responsável (os que ele cadastrou). O vendedor
 * vem do token. Cliente novo entra com ele como responsável; CPF repetido é 409 com o cliente existente.
 * Idempotência pelo id do aplicativo: 201, 200 (mesmo conteúdo) ou 409.
 */
@Service
public class ClientesDoVendedor {

    private static final int LIMITE_DA_LISTA = 100;

    public record Dados(UUID id, String nome, String cpf, LocalDate dataNascimento, String telefone, String whatsapp,
            String observacoes) {
    }

    /**
     * {@code cidade} faz parte do diretório da loja. {@code meu} diz se ele é o responsável; só nesses vêm
     * {@code dataNascimento}, {@code whatsapp} e {@code observacoes} (nos outros, vazios).
     */
    public record Cliente(UUID id, String nome, String cpf, String telefone, String cidade, boolean meu,
            LocalDate dataNascimento, String whatsapp, String observacoes, boolean ativo) {
    }

    public record Resultado(Cliente cliente, boolean jaExistia) {
    }

    private final JdbcTemplate jdbc;
    private final ServicoDeAuditoria auditoria;
    private final OrigemDoDispositivo origem;
    private final Clock relogio;

    ClientesDoVendedor(JdbcTemplate jdbc, ServicoDeAuditoria auditoria, OrigemDoDispositivo origem, Clock relogio) {
        this.jdbc = jdbc;
        this.auditoria = auditoria;
        this.origem = origem;
        this.relogio = relogio;
    }

    @Transactional
    public Resultado criar(UUID vendedorId, UUID usuarioId, UUID aparelho, Dados pedido) {
        if (pedido == null) {
            throw Validacoes.recusar("cliente", "Informe os dados do cliente.");
        }
        UUID id = Validacoes.idDoPedido(pedido.id());
        String nome = Validacoes.limpar(pedido.nome());
        if (nome == null) {
            throw Validacoes.recusar("nome", "Informe o nome do cliente.");
        }
        if (nome.length() > 200) {
            throw Validacoes.recusar("nome", "Use no máximo 200 caracteres.");
        }
        String cpf = Validacoes.cpfOpcional(pedido.cpf());
        String telefone = curto(pedido.telefone(), "telefone");
        String whatsapp = curto(pedido.whatsapp(), "whatsapp");
        String observacoes = Validacoes.limpar(pedido.observacoes());
        if (observacoes != null && observacoes.length() > 2000) {
            throw Validacoes.recusar("observacoes", "Use no máximo 2000 caracteres.");
        }
        LocalDate nascimento = pedido.dataNascimento();
        if (nascimento != null && (nascimento.isAfter(relogio.instant().atZone(ZoneOffset.UTC).toLocalDate())
                || nascimento.getYear() < 1900)) {
            throw Validacoes.recusar("dataNascimento", "Confira a data de nascimento.");
        }

        // Mesmo id: só é "o mesmo cliente" se for dele e com os mesmos dados (nunca diz de quem é outro).
        List<Map<String, Object>> existente = jdbc.queryForList("""
                SELECT nome, cpf, data_nascimento, telefone, whatsapp, observacoes, vendedor_responsavel_id, deleted_at
                  FROM corona.clientes WHERE id = ?""", id);
        if (!existente.isEmpty()) {
            Map<String, Object> e = existente.get(0);
            boolean igual = e.get("deleted_at") == null && vendedorId.equals(e.get("vendedor_responsavel_id")) && nome.equals(e.get("nome"))
                    && Objects.equals(cpf, e.get("cpf")) && Objects.equals(telefone, e.get("telefone"))
                    && Objects.equals(whatsapp, e.get("whatsapp")) && Objects.equals(observacoes, e.get("observacoes"))
                    && Objects.equals(nascimento, e.get("data_nascimento") == null ? null
                            : ((java.sql.Date) e.get("data_nascimento")).toLocalDate());
            if (!igual) {
                throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.CLIENTE_JA_REGISTRADO_DIFERENTE);
            }
            return new Resultado(ver(id, vendedorId), true);
        }
        CpfDeCliente.exigirLivre(jdbc, cpf, id);
        if (aparelho != null) {
            origem.definirNaTransacaoAtual(aparelho);
        }
        jdbc.update("""
                INSERT INTO corona.clientes (id, nome, cpf, data_nascimento, telefone, whatsapp, observacoes, vendedor_responsavel_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", id, nome, cpf, nascimento, telefone, whatsapp, observacoes, vendedorId);
        auditoria.registrar(Ator.usuario(usuarioId), "CLIENTE_CRIADO_PELO_VENDEDOR", "clientes", id, null,
                Map.of("vendedor_id", vendedorId.toString()));
        return new Resultado(ver(id, vendedorId), false);
    }

    private static final String COLUNAS = """
            SELECT id, nome, cpf, telefone, endereco_cidade, (vendedor_responsavel_id = ?) AS meu,
                   CASE WHEN vendedor_responsavel_id = ? THEN data_nascimento END AS data_nascimento,
                   CASE WHEN vendedor_responsavel_id = ? THEN whatsapp END AS whatsapp,
                   CASE WHEN vendedor_responsavel_id = ? THEN observacoes END AS observacoes, ativo
              FROM corona.clientes""";

    /**
     * Os clientes ativos da loja, por nome (os dele primeiro não: só por nome); {@code busca} procura no nome, telefone,
     * CPF e, só nos clientes dele, no WhatsApp.
     */
    public List<Cliente> listar(UUID vendedorId, String busca) {
        String texto = busca == null ? "" : busca.strip().toLowerCase(java.util.Locale.ROOT);
        String digitos = texto.replaceAll("\\D", "");
        return jdbc.query(COLUNAS + """

                 WHERE deleted_at IS NULL AND ativo
                   AND (? = '' OR lower(nome) LIKE '%' || ? || '%'
                        OR (? <> '' AND (regexp_replace(coalesce(telefone, ''), '\\D', '', 'g') LIKE '%' || ? || '%'
                                         OR (vendedor_responsavel_id = ?
                                             AND regexp_replace(coalesce(whatsapp, ''), '\\D', '', 'g') LIKE '%' || ? || '%')
                                         OR cpf LIKE ? || '%')))
                 ORDER BY lower(nome), id LIMIT\s""" + LIMITE_DA_LISTA, (rs, n) -> de(rs),
                vendedorId, vendedorId, vendedorId, vendedorId, texto, escapar(texto), digitos, digitos, vendedorId, digitos,
                digitos);
    }

    /** Qualquer cliente ativo da loja (404 se não existe, está excluído ou inativo). */
    public Cliente ver(UUID id, UUID vendedorId) {
        List<Cliente> achados = jdbc.query(COLUNAS + " WHERE id = ? AND deleted_at IS NULL", (rs, n) -> de(rs),
                vendedorId, vendedorId, vendedorId, vendedorId, id);
        if (achados.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.CADASTRO_NAO_ENCONTRADO);
        }
        return achados.get(0);
    }

    private static Cliente de(java.sql.ResultSet rs) throws java.sql.SQLException {
        java.sql.Date nascimento = rs.getDate("data_nascimento");
        return new Cliente(rs.getObject("id", UUID.class), rs.getString("nome"), rs.getString("cpf"),
                rs.getString("telefone"), rs.getString("endereco_cidade"), rs.getBoolean("meu"),
                nascimento == null ? null : nascimento.toLocalDate(), rs.getString("whatsapp"),
                rs.getString("observacoes"), rs.getBoolean("ativo"));
    }

    /** Escapa os curingas do LIKE para o texto digitado valer ao pé da letra. */
    private static String escapar(String texto) {
        return texto.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String curto(String texto, String campo) {
        String limpo = Validacoes.limpar(texto);
        if (limpo != null && limpo.length() > 30) {
            throw Validacoes.recusar(campo, "Use no máximo 30 caracteres.");
        }
        return limpo;
    }
}
