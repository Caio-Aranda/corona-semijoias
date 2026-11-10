package br.com.coronasemijoias.api.cadastros;

import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria;
import java.time.Clock;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ServicoDeCategorias extends ServicoDeCadastroAtivavel<Categoria> {

    private final RepositorioDeCategorias categorias;
    private final JdbcTemplate jdbc;

    public ServicoDeCategorias(RepositorioDeCategorias categorias, Clock relogio, JdbcTemplate jdbc,
            ServicoDeAuditoria auditoria) {
        super(categorias, relogio, auditoria, "categorias");
        this.categorias = categorias;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Page<Categoria> listar(String busca, String situacao, Integer pagina, Integer tamanho) {
        return categorias.buscar(padraoDeTexto(busca), Situacao.ler(situacao).valores(), pagina(pagina, tamanho));
    }

    @Transactional
    public Categoria criar(UUID id, String nome) {
        return gravarNovo(new Categoria(Validacoes.idDoPedido(id), nome.strip()));
    }

    @Transactional
    public Categoria editar(UUID id, Integer versao, String nome) {
        return editarComVersao(id, Validacoes.versaoDoPedido(versao), categoria -> categoria.alterar(nome.strip()));
    }

    /** Quem é usado por algum produto não sai: a dona troca antes (glossário, parte 6, tarefa 1.5). */
    @Override
    @Transactional
    public void excluir(UUID id) {
        obter(id);
        Boolean emUso = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM corona.produtos WHERE categoria_id = ? AND deleted_at IS NULL)", Boolean.class, id);
        if (Boolean.TRUE.equals(emUso)) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.CADASTRO_EM_USO_POR_PRODUTOS);
        }
        super.excluir(id);
    }
}
