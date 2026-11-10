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
public class ServicoDePracas extends ServicoDeCadastro<Praca> {

    private final RepositorioDePracas pracas;
    private final JdbcTemplate jdbc;

    public ServicoDePracas(RepositorioDePracas pracas, Clock relogio, JdbcTemplate jdbc) {
        super(pracas, relogio);
        this.pracas = pracas;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Page<Praca> listar(String busca, Integer pagina, Integer tamanho) {
        return pracas.buscar(padraoDeTexto(busca), pagina(pagina, tamanho));
    }

    @Transactional
    public Praca criar(String nome) {
        return gravarNovo(new Praca(nome.strip()));
    }

    @Transactional
    public Praca editar(UUID id, Integer versao, String nome) {
        return editarComVersao(id, Validacoes.versaoDoPedido(versao), praca -> praca.alterar(nome.strip()));
    }

    /** Quem está no cadastro de algum vendedor não sai: a dona troca antes (glossário, parte 6). */
    @Override
    @Transactional
    public void excluir(UUID id) {
        obter(id);
        Boolean emUso = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM corona.vendedores WHERE praca_id = ? AND deleted_at IS NULL)", Boolean.class, id);
        if (Boolean.TRUE.equals(emUso)) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.CADASTRO_EM_USO);
        }
        super.excluir(id);
    }
}
