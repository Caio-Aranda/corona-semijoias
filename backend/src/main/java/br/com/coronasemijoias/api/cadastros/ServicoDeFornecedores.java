package br.com.coronasemijoias.api.cadastros;

import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import java.time.Clock;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ServicoDeFornecedores extends ServicoDeCadastro<Fornecedor> {

    /** Os campos de um fornecedor como a dona preencheu. */
    public record Dados(String razaoSocial, String cnpj, String contatoNome, String telefone, String email,
            Endereco endereco) {
    }

    private final RepositorioDeFornecedores fornecedores;
    private final JdbcTemplate jdbc;

    public ServicoDeFornecedores(RepositorioDeFornecedores fornecedores, Clock relogio, JdbcTemplate jdbc) {
        super(fornecedores, relogio);
        this.fornecedores = fornecedores;
        this.jdbc = jdbc;
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

    /** Quem é usado por algum produto não sai: a dona troca antes (glossário, parte 6, tarefa 1.5). */
    @Override
    @Transactional
    public void excluir(UUID id) {
        obter(id);
        Boolean emUso = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM corona.produtos WHERE fornecedor_id = ? AND deleted_at IS NULL)", Boolean.class, id);
        if (Boolean.TRUE.equals(emUso)) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.CADASTRO_EM_USO_POR_PRODUTOS);
        }
        super.excluir(id);
    }
}
