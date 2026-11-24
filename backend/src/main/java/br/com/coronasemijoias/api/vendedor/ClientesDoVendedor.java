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
import java.util.LinkedHashMap;
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

    /** Um campo alterado no computador: o valor que ele tinha ({@code antes}) e o novo ({@code depois}). */
    public record Alteracao(String antes, String depois) {
    }

    /** Campos do cadastro que o vendedor altera (nome no app → coluna). Os outros (responsável, ativo) são da dona. */
    private static final Map<String, String> CAMPOS_DO_VENDEDOR = Map.of(
            "nome", "nome", "cpf", "cpf", "telefone", "telefone", "whatsapp", "whatsapp",
            "observacoes", "observacoes", "dataNascimento", "data_nascimento");

    /**
     * Alteração do cadastro feita pelo vendedor, campo a campo (tarefa 5.5; ADR-0013, item 8). Só cliente que ele atende.
     * Cada campo recebe o valor novo; se a dona mudou o mesmo campo nesse meio-tempo (o valor atual não é o "antes" do
     * computador), vale a última chegada ao servidor e o valor substituído vai para a auditoria, para a dona ver e voltar
     * (dúvida D1). Campos fora da lista do vendedor são ignorados.
     */
    @Transactional
    public void alterar(UUID vendedorId, UUID usuarioId, UUID aparelho, UUID clienteId, Map<String, Alteracao> campos) {
        if (clienteId == null || campos == null) {
            throw Validacoes.recusar("cliente", "Informe o cliente e o que mudou.");
        }
        List<Map<String, Object>> achado = jdbc.queryForList("""
                SELECT nome, cpf, data_nascimento, telefone, whatsapp, observacoes, vendedor_responsavel_id
                  FROM corona.clientes WHERE id = ? AND deleted_at IS NULL FOR UPDATE""", clienteId);
        if (achado.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.CADASTRO_NAO_ENCONTRADO);
        }
        Map<String, Object> atual = achado.get(0);
        if (!vendedorId.equals(atual.get("vendedor_responsavel_id"))) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.CLIENTE_DE_OUTRO_VENDEDOR);
        }
        VendedorAtivo.exigir(jdbc, vendedorId);
        Map<String, Object> novos = new LinkedHashMap<>();
        Map<String, Object> antesNoBanco = new LinkedHashMap<>();
        Map<String, Object> substituidos = new LinkedHashMap<>();
        for (Map.Entry<String, Alteracao> e : campos.entrySet()) {
            String coluna = CAMPOS_DO_VENDEDOR.get(e.getKey());
            if (coluna == null || e.getValue() == null) {
                continue;
            }
            Object novo = valorNovo(e.getKey(), e.getValue().depois(), clienteId);
            Object noBanco = atual.get(coluna) instanceof java.sql.Date d ? d.toLocalDate() : atual.get(coluna);
            String noBancoTexto = noBanco == null ? null : noBanco.toString();
            if (!Objects.equals(noBancoTexto, Validacoes.limpar(e.getValue().antes()))) {
                substituidos.put(coluna, noBancoTexto);
            }
            if (!Objects.equals(noBanco, novo)) {
                novos.put(coluna, novo);
                antesNoBanco.put(coluna, noBancoTexto);
            }
        }
        if (novos.isEmpty()) {
            return;
        }
        if (aparelho != null) {
            origem.definirNaTransacaoAtual(aparelho);
        }
        StringBuilder sql = new StringBuilder("UPDATE corona.clientes SET ");
        List<Object> valores = new java.util.ArrayList<>();
        for (Map.Entry<String, Object> n : novos.entrySet()) {
            if (!valores.isEmpty()) {
                sql.append(", ");
            }
            sql.append(n.getKey()).append(" = ?");
            valores.add(n.getValue());
        }
        sql.append(" WHERE id = ?");
        valores.add(clienteId);
        jdbc.update(sql.toString(), valores.toArray());
        Map<String, Object> depois = new LinkedHashMap<>();
        novos.forEach((k, v) -> depois.put(k, v == null ? null : v.toString()));
        auditoria.registrar(Ator.usuario(usuarioId), "CLIENTE_ALTERADO_PELO_VENDEDOR", "clientes", clienteId, antesNoBanco, depois);
        substituidos.keySet().retainAll(novos.keySet());
        if (!substituidos.isEmpty()) {
            Map<String, Object> novosDosSubstituidos = new LinkedHashMap<>();
            substituidos.keySet().forEach(k -> novosDosSubstituidos.put(k, depois.get(k)));
            auditoria.registrar(Ator.usuario(usuarioId), "CLIENTE_VALOR_SUBSTITUIDO", "clientes", clienteId, substituidos,
                    novosDosSubstituidos);
        }
    }

    /** O valor novo de um campo, com as mesmas conferências do cadastro. */
    private Object valorNovo(String campo, String digitado, UUID clienteId) {
        return switch (campo) {
            case "nome" -> {
                String nome = Validacoes.limpar(digitado);
                if (nome == null) {
                    throw Validacoes.recusar("nome", "Informe o nome do cliente.");
                }
                if (nome.length() > 200) {
                    throw Validacoes.recusar("nome", "Use no máximo 200 caracteres.");
                }
                yield nome;
            }
            case "cpf" -> {
                String cpf = Validacoes.cpfOpcional(digitado);
                CpfDeCliente.exigirLivre(jdbc, cpf, clienteId);
                yield cpf;
            }
            case "telefone", "whatsapp" -> curto(digitado, campo);
            case "observacoes" -> {
                String obs = Validacoes.limpar(digitado);
                if (obs != null && obs.length() > 2000) {
                    throw Validacoes.recusar("observacoes", "Use no máximo 2000 caracteres.");
                }
                yield obs;
            }
            default -> {
                String texto = Validacoes.limpar(digitado);
                if (texto == null) {
                    yield null;
                }
                LocalDate nascimento;
                try {
                    nascimento = LocalDate.parse(texto);
                } catch (java.time.format.DateTimeParseException e) {
                    throw Validacoes.recusar("dataNascimento", "Confira a data de nascimento.");
                }
                if (nascimento.isAfter(relogio.instant().atZone(ZoneOffset.UTC).toLocalDate()) || nascimento.getYear() < 1900) {
                    throw Validacoes.recusar("dataNascimento", "Confira a data de nascimento.");
                }
                yield nascimento;
            }
        };
    }
    private static final String COLUNAS = """
            SELECT id, nome, cpf, telefone, endereco_cidade, (vendedor_responsavel_id = ?) AS meu,
                   CASE WHEN vendedor_responsavel_id = ? THEN data_nascimento END AS data_nascimento,
                   CASE WHEN vendedor_responsavel_id = ? THEN whatsapp END AS whatsapp,
                   CASE WHEN vendedor_responsavel_id = ? THEN observacoes END AS observacoes, ativo
              FROM corona.clientes""";

    /**
     * Os clientes ativos DO PRÓPRIO REPRESENTANTE, por nome (a lista de cada um é só dele, ADR-0016, item 4); {@code busca}
     * procura no nome, telefone, CPF e WhatsApp.
     */
    public List<Cliente> listar(UUID vendedorId, String busca) {
        String texto = busca == null ? "" : busca.strip().toLowerCase(java.util.Locale.ROOT);
        String digitos = texto.replaceAll("\\D", "");
        return jdbc.query(COLUNAS + """

                 WHERE deleted_at IS NULL AND ativo AND vendedor_responsavel_id = ?
                   AND (? = '' OR lower(nome) LIKE '%' || ? || '%'
                        OR (? <> '' AND (regexp_replace(coalesce(telefone, ''), '\\D', '', 'g') LIKE '%' || ? || '%'
                                         OR (vendedor_responsavel_id = ?
                                             AND regexp_replace(coalesce(whatsapp, ''), '\\D', '', 'g') LIKE '%' || ? || '%')
                                         OR cpf LIKE ? || '%')))
                 ORDER BY lower(nome), id LIMIT\s""" + LIMITE_DA_LISTA, (rs, n) -> de(rs),
                vendedorId, vendedorId, vendedorId, vendedorId, vendedorId, texto, escapar(texto), digitos, digitos, vendedorId,
                digitos, digitos);
    }

    /**
     * Um cliente do próprio representante (404 se não existe, foi excluído ou é de outro representante: cada um só vê os seus,
     * ADR-0016, item 4 e §23.5). Inativo ainda se vê pelo id (histórico).
     */
    public Cliente ver(UUID id, UUID vendedorId) {
        List<Cliente> achados = jdbc.query(COLUNAS + " WHERE id = ? AND deleted_at IS NULL AND vendedor_responsavel_id = ?",
                (rs, n) -> de(rs), vendedorId, vendedorId, vendedorId, vendedorId, id, vendedorId);
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
