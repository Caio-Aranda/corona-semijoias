package br.com.coronasemijoias.api.cadastros;

import java.time.Clock;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ServicoDeFornecedores extends ServicoDeCadastro<Fornecedor> {

    /** Os campos de um fornecedor como a dona preencheu. */
    public record Dados(String razaoSocial, String cnpj, String contatoNome, String telefone, String email,
            Endereco endereco) {
    }

    private final RepositorioDeFornecedores fornecedores;

    public ServicoDeFornecedores(RepositorioDeFornecedores fornecedores, Clock relogio) {
        super(fornecedores, relogio);
        this.fornecedores = fornecedores;
    }

    @Transactional(readOnly = true)
    public Page<Fornecedor> listar(String busca, Integer pagina, Integer tamanho) {
        return fornecedores.buscar(padraoDeTexto(busca), padraoDeDocumento(busca), pagina(pagina, tamanho));
    }

    @Transactional
    public Fornecedor criar(Dados d) {
        return gravarNovo(new Fornecedor(d.razaoSocial().strip(), Validacoes.cnpjOpcional(d.cnpj()),
                Validacoes.limpar(d.contatoNome()), Validacoes.limpar(d.telefone()), Validacoes.limpar(d.email()),
                Endereco.limpo(d.endereco())));
    }

    @Transactional
    public Fornecedor editar(UUID id, Integer versao, Dados d) {
        String cnpj = Validacoes.cnpjOpcional(d.cnpj());
        return editarComVersao(id, Validacoes.versaoDoPedido(versao), fornecedor -> fornecedor.alterar(
                d.razaoSocial().strip(), cnpj, Validacoes.limpar(d.contatoNome()), Validacoes.limpar(d.telefone()),
                Validacoes.limpar(d.email()), Endereco.limpo(d.endereco())));
    }
}
