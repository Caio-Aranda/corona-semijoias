package br.com.coronasemijoias.api.cadastros;

import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria;
import java.time.Clock;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Clientes (da loja). O CPF é conferido aqui (o servidor valida), guardado só com os dígitos e é ÚNICO entre os clientes
 * não excluídos: CPF repetido é recusado com 409 mostrando o cliente que já existe (11/11/2026; ESPECIFICACAO §9 e §10).
 */
@Service
public class ServicoDeClientes extends ServicoDeCadastroAtivavel<Cliente> {

    /** Os campos de um cliente como foram preenchidos. */
    public record Dados(String nome, String cpf, LocalDate dataNascimento, String telefone, String whatsapp, Endereco endereco,
            String observacoes, UUID vendedorResponsavelId, String documento, String documentoTipo, UUID indicadoPorClienteId,
            String indicadoPorNome, String comoConheceu, BigDecimal comissaoPercentual) {

        /** Sem os campos do consignado (os cadastros de sempre). */
        public Dados(String nome, String cpf, LocalDate dataNascimento, String telefone, String whatsapp, Endereco endereco,
                String observacoes, UUID vendedorResponsavelId) {
            this(nome, cpf, dataNascimento, telefone, whatsapp, endereco, observacoes, vendedorResponsavelId, null, null, null, null,
                    null, null);
        }
    }

    /** Documento já resolvido: CPF conferido (e único) ou documento livre de outro tipo, que não exige CPF válido. */
    private record DocumentoResolvido(String cpf, String documento, String tipo) {
    }

    private final RepositorioDeClientes clientes;
    private final JdbcTemplate jdbc;
    private final Clock relogio;

    public ServicoDeClientes(RepositorioDeClientes clientes, Clock relogio, JdbcTemplate jdbc,
            ServicoDeAuditoria auditoria) {
        super(clientes, relogio, auditoria, "clientes");
        this.clientes = clientes;
        this.jdbc = jdbc;
        this.relogio = relogio;
    }

    @Transactional(readOnly = true)
    public Page<Cliente> listar(String busca, String situacao, Integer pagina, Integer tamanho) {
        return clientes.buscar(padraoDeTexto(busca), padraoDeDocumento(busca), Situacao.ler(situacao).valores(), pagina(pagina, tamanho));
    }

    @Transactional
    public Cliente criar(UUID id, Dados d) {
        conferirVendedorResponsavel(d.vendedorResponsavelId());
        DocumentoResolvido doc = resolverDocumento(d);
        UUID novoId = Validacoes.idDoPedido(id);
        CpfDeCliente.exigirLivre(jdbc, doc.cpf(), novoId);
        BigDecimal comissao = comissao(d.comissaoPercentual());
        conferirIndicacao(d, novoId);
        Cliente novo = new Cliente(novoId, d.nome().strip(), doc.cpf(),
                dataDeNascimento(d.dataNascimento()), Validacoes.limpar(d.telefone()), Validacoes.limpar(d.whatsapp()), Endereco.limpo(d.endereco()),
                Validacoes.limpar(d.observacoes()), d.vendedorResponsavelId());
        novo.alterarConsignado(doc.documento(), doc.tipo(), d.indicadoPorClienteId(), indicadoPorNome(d), Validacoes.limpar(d.comoConheceu()),
                comissao == null ? new BigDecimal("30.00") : comissao);
        return gravarNovo(novo);
    }

    @Transactional
    public Cliente editar(UUID id, Integer versao, Dados d) {
        DocumentoResolvido doc = resolverDocumento(d);
        LocalDate nascimento = dataDeNascimento(d.dataNascimento());
        conferirVendedorResponsavel(d.vendedorResponsavelId());
        CpfDeCliente.exigirLivre(jdbc, doc.cpf(), id);
        BigDecimal comissao = comissao(d.comissaoPercentual());
        conferirIndicacao(d, id);
        return editarComVersao(id, Validacoes.versaoDoPedido(versao), cliente -> {
            // D10 (ADR-0016): o representante do cliente não muda depois de definido; salvar sem representante mantém o que já existe.
            UUID atual = cliente.getVendedorResponsavelId();
            if (atual != null && d.vendedorResponsavelId() != null && !atual.equals(d.vendedorResponsavelId())) {
                throw Validacoes.recusar("vendedorResponsavelId",
                        "O representante deste cliente não muda depois de escolhido. Deixe o mesmo representante.");
            }
            cliente.alterar(d.nome().strip(), doc.cpf(), nascimento, Validacoes.limpar(d.telefone()), Validacoes.limpar(d.whatsapp()),
                    Endereco.limpo(d.endereco()), Validacoes.limpar(d.observacoes()), atual != null ? atual : d.vendedorResponsavelId());
            cliente.alterarConsignado(doc.documento(), doc.tipo(), d.indicadoPorClienteId(), indicadoPorNome(d),
                    Validacoes.limpar(d.comoConheceu()), comissao);
        });
    }

    private static final Set<String> TIPOS_DE_DOCUMENTO = Set.of("CPF", "RG", "CI", "OUTRO");

    /**
     * O documento do cliente. Sem tipo (ou do tipo CPF) vale o CPF de sempre: só dígitos, conferido e único. De outro tipo (RG, CI
     * paraguaia, outro) é texto livre, guardado como foi escrito, sem exigir CPF válido; o CPF fica vazio.
     */
    private static DocumentoResolvido resolverDocumento(Dados d) {
        String tipo = Validacoes.limpar(d.documentoTipo());
        if (tipo != null && !TIPOS_DE_DOCUMENTO.contains(tipo)) {
            throw Validacoes.recusar("documentoTipo", "Escolha o tipo do documento: CPF, RG, CI ou outro.");
        }
        if (tipo == null || "CPF".equals(tipo)) {
            String cpf = Validacoes.cpfOpcional(d.documento() != null ? d.documento() : d.cpf());
            return new DocumentoResolvido(cpf, cpf, cpf == null ? null : "CPF");
        }
        String documento = Validacoes.limpar(d.documento());
        if (documento == null) {
            throw Validacoes.recusar("documento", "Escreva o número do documento ou deixe o tipo em branco.");
        }
        return new DocumentoResolvido(null, documento, tipo);
    }

    /** Comissão do cliente: de 0 a 40; vazia vale o padrão (30 ao criar, a que já existe ao editar). */
    private static BigDecimal comissao(BigDecimal informada) {
        if (informada == null) {
            return null;
        }
        if (informada.signum() < 0 || informada.compareTo(new BigDecimal("40")) > 0) {
            throw Validacoes.recusar("comissaoPercentual", "A comissão do cliente vai de 0% a 40%.");
        }
        return informada.setScale(2, RoundingMode.HALF_UP);
    }

    private static String indicadoPorNome(Dados d) {
        return d.indicadoPorClienteId() != null ? null : Validacoes.limpar(d.indicadoPorNome());
    }

    /** Quem indicou é um cliente que existe (e não o próprio), ou um nome livre. */
    private void conferirIndicacao(Dados d, UUID idDoProprio) {
        UUID indicador = d.indicadoPorClienteId();
        if (indicador == null) {
            return;
        }
        Boolean existe = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM corona.clientes WHERE id = ? AND deleted_at IS NULL AND id <> ?)", Boolean.class, indicador,
                idDoProprio);
        if (!Boolean.TRUE.equals(existe)) {
            throw Validacoes.recusar("indicadoPorClienteId", Mensagens.OPCAO_INEXISTENTE);
        }
    }

    /** A data de nascimento é opcional; não pode ser no futuro (hoje vale) nem antes de 1900. */
    private LocalDate dataDeNascimento(LocalDate data) {
        if (data == null) {
            return null;
        }
        if (data.isAfter(relogio.instant().atZone(ZoneOffset.UTC).toLocalDate())) {
            throw Validacoes.recusar("dataNascimento", "A data de nascimento não pode ser no futuro.");
        }
        if (data.getYear() < 1900) {
            throw Validacoes.recusar("dataNascimento", "Confira a data de nascimento: ela parece muito antiga.");
        }
        return data;
    }

    /** O vendedor responsável, quando vem, precisa existir (e não estar excluído). */
    private void conferirVendedorResponsavel(UUID vendedorId) {
        if (vendedorId == null) {
            return;
        }
        Boolean existe = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM corona.vendedores WHERE id = ? AND deleted_at IS NULL)", Boolean.class, vendedorId);
        if (!Boolean.TRUE.equals(existe)) {
            throw Validacoes.recusar("vendedorResponsavelId", Mensagens.OPCAO_INEXISTENTE);
        }
    }
}
