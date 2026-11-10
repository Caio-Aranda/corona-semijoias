package br.com.coronasemijoias.api.cadastros;

import java.time.Clock;
import java.util.UUID;
import org.springframework.data.domain.Page;
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

    public ServicoDeClientes(RepositorioDeClientes clientes, Clock relogio) {
        super(clientes, relogio);
        this.clientes = clientes;
    }

    @Transactional(readOnly = true)
    public Page<Cliente> listar(String busca, Integer pagina, Integer tamanho) {
        return clientes.buscar(padraoDeTexto(busca), padraoDeDocumento(busca), pagina(pagina, tamanho));
    }

    @Transactional
    public Cliente criar(UUID id, Dados d) {
        return gravarNovo(new Cliente(Validacoes.idDoPedido(id), d.nome().strip(), Validacoes.cpfOpcional(d.cpf()),
                Validacoes.limpar(d.telefone()), Validacoes.limpar(d.whatsapp()), Endereco.limpo(d.endereco()),
                Validacoes.limpar(d.observacoes()), d.vendedorResponsavelId()));
    }

    @Transactional
    public Cliente editar(UUID id, Integer versao, Dados d) {
        String cpf = Validacoes.cpfOpcional(d.cpf());
        return editarComVersao(id, Validacoes.versaoDoPedido(versao), cliente -> cliente.alterar(
                d.nome().strip(), cpf, Validacoes.limpar(d.telefone()), Validacoes.limpar(d.whatsapp()),
                Endereco.limpo(d.endereco()), Validacoes.limpar(d.observacoes()), d.vendedorResponsavelId()));
    }
}
