package br.com.coronasemijoias.api.vendedor;

import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria;
import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria.Ator;
import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.comum.persistencia.OrigemDoDispositivo;
import br.com.coronasemijoias.api.consignado.CalculoDoAcertoDaNota;
import br.com.coronasemijoias.api.consignado.ServicoDeAlertasDoConsignado;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * O cadastro do cliente do consignado, formato 2 de {@code CLIENTE_NOVO} e {@code CLIENTE_ALTERADO} (ADR-0016, item 4;
 * ESPECIFICACAO §23.5): documento livre, endereço, indicação, comissão do cliente (0% a 40%) e os hashes das três fotos
 * (os arquivos sobem à parte, por {@code FotosDosClientes}). O servidor exige nome, endereço (rua, número, bairro, cidade,
 * UF) e telefone ou WhatsApp; as fotos NÃO são exigidas aqui (a falta de envio nunca trava a nota). Documento repetido é
 * aceito com alerta. O cliente nasce com o representante como responsável e só ele o altera.
 */
@Service
public class ClientesDoConsignado {

    public record Novo(UUID id, String nome, String documento, String documentoTipo, LocalDate dataNascimento, String telefone,
            String whatsapp, String enderecoLogradouro, String enderecoNumero, String enderecoComplemento, String enderecoBairro,
            String enderecoCidade, String enderecoUf, String enderecoCep, UUID indicadoPorClienteId, String indicadoPorNome,
            String comoConheceu, BigDecimal comissaoPercentual, String observacoes, String fotoHash, String fotoDocumentoFrenteHash,
            String fotoDocumentoVersoHash) {
    }

    /** Um campo alterado: o valor que o computador tinha e o novo (texto). */
    public record Alteracao(String antes, String depois) {
    }

    private static final Set<String> TIPOS_DE_DOCUMENTO = Set.of("CPF", "RG", "CI", "OUTRO");

    /** Campos que o representante altera no formato 2 (nome no app → coluna). */
    private static final Map<String, String> CAMPOS = campos();

    private static Map<String, String> campos() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("nome", "nome");
        m.put("documento", "documento");
        m.put("documentoTipo", "documento_tipo");
        m.put("dataNascimento", "data_nascimento");
        m.put("telefone", "telefone");
        m.put("whatsapp", "whatsapp");
        m.put("enderecoLogradouro", "endereco_logradouro");
        m.put("enderecoNumero", "endereco_numero");
        m.put("enderecoComplemento", "endereco_complemento");
        m.put("enderecoBairro", "endereco_bairro");
        m.put("enderecoCidade", "endereco_cidade");
        m.put("enderecoUf", "endereco_uf");
        m.put("enderecoCep", "endereco_cep");
        m.put("indicadoPorClienteId", "indicado_por_cliente_id");
        m.put("indicadoPorNome", "indicado_por_nome");
        m.put("comoConheceu", "como_conheceu");
        m.put("comissaoPercentual", "comissao_percentual");
        m.put("observacoes", "observacoes");
        m.put("fotoHash", "foto_hash");
        m.put("fotoDocumentoFrenteHash", "foto_documento_frente_hash");
        m.put("fotoDocumentoVersoHash", "foto_documento_verso_hash");
        return m;
    }

    private final JdbcTemplate jdbc;
    private final ServicoDeAuditoria auditoria;
    private final OrigemDoDispositivo origem;
    private final ServicoDeAlertasDoConsignado alertas;

    public ClientesDoConsignado(JdbcTemplate jdbc, ServicoDeAuditoria auditoria, OrigemDoDispositivo origem,
            ServicoDeAlertasDoConsignado alertas) {
        this.jdbc = jdbc;
        this.auditoria = auditoria;
        this.origem = origem;
        this.alertas = alertas;
    }

    /** @return {@code true} se o cliente já existia com os mesmos dados (reenvio inofensivo) */
    @Transactional
    public boolean criar(UUID vendedorId, UUID usuarioId, UUID aparelho, Novo p) {
        if (p == null) {
            throw Validacoes.recusar("cliente", "Informe os dados do cliente.");
        }
        UUID id = Validacoes.idDoPedido(p.id());
        String nome = obrigatorio(p.nome(), "nome", "Informe o nome do cliente.", 200);
        String telefone = limitado(p.telefone(), "telefone", 30);
        String whatsapp = limitado(p.whatsapp(), "whatsapp", 30);
        if (telefone == null && whatsapp == null) {
            throw Validacoes.recusar("telefone", "Informe o telefone ou o WhatsApp do cliente.");
        }
        String rua = obrigatorio(p.enderecoLogradouro(), "enderecoLogradouro", "Informe a rua do cliente.", 200);
        String numero = obrigatorio(p.enderecoNumero(), "enderecoNumero", "Informe o número da casa do cliente.", 30);
        String bairro = obrigatorio(p.enderecoBairro(), "enderecoBairro", "Informe o bairro do cliente.", 100);
        String cidade = obrigatorio(p.enderecoCidade(), "enderecoCidade", "Informe a cidade do cliente.", 100);
        String uf = obrigatorio(p.enderecoUf(), "enderecoUf", "Informe o estado do cliente.", 30);
        String documento = limitado(p.documento(), "documento", 40);
        String tipo = p.documentoTipo() == null ? null : p.documentoTipo().strip();
        if (tipo != null && !TIPOS_DE_DOCUMENTO.contains(tipo)) {
            throw Validacoes.recusar("documentoTipo", "Escolha o tipo do documento.");
        }
        BigDecimal comissao = comissao(p.comissaoPercentual());
        if (p.indicadoPorClienteId() != null && p.indicadoPorNome() != null && Validacoes.limpar(p.indicadoPorNome()) != null) {
            throw Validacoes.recusar("indicadoPorNome", "Escolha o cliente que indicou ou escreva o nome, não os dois.");
        }
        String indicadoPorNome = limitado(p.indicadoPorNome(), "indicadoPorNome", 200);
        if (p.indicadoPorClienteId() != null) {
            exigirClienteDele(p.indicadoPorClienteId(), vendedorId);
        }
        String foto = hash(p.fotoHash(), "fotoHash");
        String frente = hash(p.fotoDocumentoFrenteHash(), "fotoDocumentoFrenteHash");
        String verso = hash(p.fotoDocumentoVersoHash(), "fotoDocumentoVersoHash");
        LocalDate nascimento = p.dataNascimento();
        if (nascimento != null && (nascimento.isAfter(LocalDate.now().plusDays(1)) || nascimento.getYear() < 1900)) {
            throw Validacoes.recusar("dataNascimento", "Confira a data de nascimento.");
        }

        VendedorAtivo.exigir(jdbc, vendedorId);
        List<Map<String, Object>> existente = jdbc.queryForList(
                "SELECT nome, documento, telefone, vendedor_responsavel_id, deleted_at FROM corona.clientes WHERE id = ?", id);
        if (!existente.isEmpty()) {
            Map<String, Object> e = existente.get(0);
            boolean igual = e.get("deleted_at") == null && vendedorId.equals(e.get("vendedor_responsavel_id")) && nome.equals(e.get("nome"))
                    && Objects.equals(documento, e.get("documento")) && Objects.equals(telefone, e.get("telefone"));
            if (!igual) {
                throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.CLIENTE_JA_REGISTRADO_DIFERENTE);
            }
            return true;
        }
        if (aparelho != null) {
            origem.definirNaTransacaoAtual(aparelho);
        }
        jdbc.update("""
                INSERT INTO corona.clientes (id, nome, documento, documento_tipo, data_nascimento, telefone, whatsapp,
                    endereco_logradouro, endereco_numero, endereco_complemento, endereco_bairro, endereco_cidade, endereco_uf,
                    endereco_cep, indicado_por_cliente_id, indicado_por_nome, como_conheceu, comissao_percentual, observacoes,
                    foto_hash, foto_documento_frente_hash, foto_documento_verso_hash, vendedor_responsavel_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, nome, documento, tipo, nascimento,
                telefone, whatsapp, rua, numero, limitado(p.enderecoComplemento(), "enderecoComplemento", 100), bairro, cidade, uf,
                limitado(p.enderecoCep(), "enderecoCep", 20), p.indicadoPorClienteId(), indicadoPorNome,
                limitado(p.comoConheceu(), "comoConheceu", 200), comissao, limitado(p.observacoes(), "observacoes", 2000), foto, frente,
                verso, vendedorId);
        alertarDocumentoRepetido(vendedorId, id, documento);
        auditoria.registrar(Ator.usuario(usuarioId), "CLIENTE_CRIADO_PELO_VENDEDOR", "clientes", id, null,
                Map.of("vendedor_id", vendedorId.toString(), "formato", 2));
        return false;
    }

    /** Alteração campo a campo (formato 2). Só cliente dele; campos desconhecidos são ignorados. */
    @Transactional
    public void alterar(UUID vendedorId, UUID usuarioId, UUID aparelho, UUID clienteId, Map<String, Alteracao> campos) {
        if (clienteId == null || campos == null || campos.isEmpty()) {
            throw Validacoes.recusar("cliente", "Informe o cliente e o que mudou.");
        }
        List<Map<String, Object>> achado = jdbc.queryForList(
                "SELECT * FROM corona.clientes WHERE id = ? AND deleted_at IS NULL FOR UPDATE", clienteId);
        if (achado.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.CADASTRO_NAO_ENCONTRADO);
        }
        Map<String, Object> atual = achado.get(0);
        if (!vendedorId.equals(atual.get("vendedor_responsavel_id"))) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.CLIENTE_DE_OUTRO_VENDEDOR);
        }
        VendedorAtivo.exigir(jdbc, vendedorId);
        Map<String, Object> novos = new LinkedHashMap<>();
        Map<String, Object> antes = new LinkedHashMap<>();
        for (Map.Entry<String, Alteracao> e : campos.entrySet()) {
            String coluna = CAMPOS.get(e.getKey());
            if (coluna == null || e.getValue() == null) {
                continue;
            }
            Object novo = valor(e.getKey(), e.getValue().depois(), vendedorId);
            Object noBanco = atual.get(coluna) instanceof java.sql.Date d ? d.toLocalDate() : atual.get(coluna);
            boolean igual = noBanco instanceof BigDecimal b && novo instanceof BigDecimal n ? b.compareTo(n) == 0 : Objects.equals(noBanco, novo);
            if (!igual) {
                novos.put(coluna, novo);
                antes.put(coluna, noBanco == null ? null : noBanco.toString());
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
        auditoria.registrar(Ator.usuario(usuarioId), "CLIENTE_ALTERADO_PELO_VENDEDOR", "clientes", clienteId, antes, depois);
        if (novos.containsKey("documento")) {
            alertarDocumentoRepetido(vendedorId, clienteId, (String) novos.get("documento"));
        }
    }

    private Object valor(String campo, String digitado, UUID vendedorId) {
        String texto = Validacoes.limpar(digitado);
        return switch (campo) {
            case "nome" -> obrigatorio(digitado, "nome", "Informe o nome do cliente.", 200);
            case "documento" -> limitado(digitado, campo, 40);
            case "documentoTipo" -> {
                if (texto != null && !TIPOS_DE_DOCUMENTO.contains(texto)) {
                    throw Validacoes.recusar(campo, "Escolha o tipo do documento.");
                }
                yield texto;
            }
            case "dataNascimento" -> {
                if (texto == null) {
                    yield null;
                }
                try {
                    LocalDate d = LocalDate.parse(texto);
                    if (d.isAfter(LocalDate.now().plusDays(1)) || d.getYear() < 1900) {
                        throw Validacoes.recusar(campo, "Confira a data de nascimento.");
                    }
                    yield d;
                } catch (java.time.format.DateTimeParseException e) {
                    throw Validacoes.recusar(campo, "Confira a data de nascimento.");
                }
            }
            case "telefone", "whatsapp", "enderecoNumero", "enderecoUf" -> limitado(digitado, campo, 30);
            case "enderecoCep" -> limitado(digitado, campo, 20);
            case "indicadoPorClienteId" -> {
                if (texto == null) {
                    yield null;
                }
                UUID cliente;
                try {
                    cliente = UUID.fromString(texto);
                } catch (IllegalArgumentException e) {
                    throw Validacoes.recusar(campo, "Escolha o cliente que indicou.");
                }
                exigirClienteDele(cliente, vendedorId);
                yield cliente;
            }
            case "comissaoPercentual" -> comissao(texto == null ? null : new BigDecimal(texto));
            case "fotoHash", "fotoDocumentoFrenteHash", "fotoDocumentoVersoHash" -> hash(texto, campo);
            case "observacoes" -> limitado(digitado, campo, 2000);
            default -> limitado(digitado, campo, 200);
        };
    }

    private BigDecimal comissao(BigDecimal valor) {
        if (valor == null) {
            return new BigDecimal("30.00");
        }
        if (!CalculoDoAcertoDaNota.comissaoValida(valor)) {
            throw new ErroDaApi(HttpStatus.UNPROCESSABLE_ENTITY, CodigoErro.COMISSAO_FORA_DO_LIMITE);
        }
        return valor.setScale(2);
    }

    private void exigirClienteDele(UUID cliente, UUID vendedorId) {
        List<UUID> dono = jdbc.queryForList("SELECT vendedor_responsavel_id FROM corona.clientes WHERE id = ? AND deleted_at IS NULL",
                UUID.class, cliente);
        if (dono.isEmpty() || !vendedorId.equals(dono.get(0))) {
            throw Validacoes.recusar("indicadoPorClienteId", "Escolha um dos seus clientes.");
        }
    }

    /** Outro cliente do mesmo representante com o mesmo documento: aceita e avisa a dona ("possível cadastro repetido"). */
    private void alertarDocumentoRepetido(UUID vendedorId, UUID clienteId, String documento) {
        if (documento == null) {
            return;
        }
        List<UUID> outros = jdbc.queryForList("""
                SELECT id FROM corona.clientes WHERE id <> ? AND deleted_at IS NULL AND vendedor_responsavel_id = ?
                   AND lower(btrim(documento)) = lower(btrim(?))""", UUID.class, clienteId, vendedorId, documento);
        if (!outros.isEmpty()) {
            alertas.registrar("DOCUMENTO_REPETIDO", vendedorId, "clientes", clienteId, Map.of("outro_cliente_id", outros.get(0).toString()));
        }
    }

    private static String hash(String texto, String campo) {
        String limpo = Validacoes.limpar(texto);
        if (limpo != null && !limpo.matches("^[0-9a-f]{64}$")) {
            throw Validacoes.recusar(campo, "A foto do cliente não está correta. Tire a foto de novo.");
        }
        return limpo;
    }

    private static String obrigatorio(String texto, String campo, String mensagem, int maximo) {
        String limpo = Validacoes.limpar(texto);
        if (limpo == null) {
            throw Validacoes.recusar(campo, mensagem);
        }
        return limitado(limpo, campo, maximo);
    }

    private static String limitado(String texto, String campo, int maximo) {
        String limpo = Validacoes.limpar(texto);
        if (limpo != null && limpo.length() > maximo) {
            throw Validacoes.recusar(campo, "Use no máximo " + maximo + " caracteres.");
        }
        return limpo;
    }
}
