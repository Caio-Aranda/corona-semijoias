package br.com.coronasemijoias.api.cadastros;

import java.time.Clock;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Clientes. O CPF é conferido aqui (o servidor valida) e guardado só com os dígitos, mas não é único:
 * CPF repetido é caso de revisão da dona na sincronização, não de recusa (ESPECIFICACAO §10).
 */
@Service
public class ServicoDeClientes extends ServicoDeCadastro<Cliente> {

    /** Os campos de um cliente como foram preenchidos. */
    public record Dados(String nome, String cpf, String telefone, String whatsapp, Endereco endereco,
            String observacoes, UUID vendedorResponsavelId) {
    }

    private final RepositorioDeClientes clientes;
    private final JdbcTemplate jdbc;

    public ServicoDeClientes(RepositorioDeClientes clientes, Clock relogio, JdbcTemplate jdbc) {
        super(clientes, relogio);
        this.clientes = clientes;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Page<Cliente> listar(String busca, Integer pagina, Integer tamanho) {
        return clientes.buscar(padraoDeTexto(busca), padraoDeDocumento(busca), pagina(pagina, tamanho));
    }

    @Transactional
    public Cliente criar(UUID id, Dados d) {
        conferirVendedorResponsavel(d.vendedorResponsavelId());
        return gravarNovo(new Cliente(Validacoes.idDoPedido(id), d.nome().strip(), Validacoes.cpfOpcional(d.cpf()),
                Validacoes.limpar(d.telefone()), Validacoes.limpar(d.whatsapp()), Endereco.limpo(d.endereco()),
                Validacoes.limpar(d.observacoes()), d.vendedorResponsavelId()));
    }

    @Transactional
    public Cliente editar(UUID id, Integer versao, Dados d) {
        String cpf = Validacoes.cpfOpcional(d.cpf());
        conferirVendedorResponsavel(d.vendedorResponsavelId());
        return editarComVersao(id, Validacoes.versaoDoPedido(versao), cliente -> cliente.alterar(
                d.nome().strip(), cpf, Validacoes.limpar(d.telefone()), Validacoes.limpar(d.whatsapp()),
                Endereco.limpo(d.endereco()), Validacoes.limpar(d.observacoes()), d.vendedorResponsavelId()));
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
