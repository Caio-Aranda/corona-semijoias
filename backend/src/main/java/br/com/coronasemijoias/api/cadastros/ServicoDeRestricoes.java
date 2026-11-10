package br.com.coronasemijoias.api.cadastros;

import java.time.Clock;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ServicoDeRestricoes extends ServicoDeCadastro<Restricao> {

    private final RepositorioDeRestricoes restricoes;

    public ServicoDeRestricoes(RepositorioDeRestricoes restricoes, Clock relogio) {
        super(restricoes, relogio);
        this.restricoes = restricoes;
    }

    @Transactional(readOnly = true)
    public Page<Restricao> listar(String busca, Integer pagina, Integer tamanho) {
        return restricoes.buscar(padraoDeTexto(busca), pagina(pagina, tamanho));
    }

    @Transactional
    public Restricao criar(String codigo, String descricao, boolean bloqueiaRemessa) {
        return gravarNovo(new Restricao(codigo.strip(), descricao.strip(), bloqueiaRemessa));
    }

    @Transactional
    public Restricao editar(UUID id, Integer versao, String codigo, String descricao, boolean bloqueiaRemessa) {
        return editarComVersao(id, Validacoes.versaoDoPedido(versao),
                restricao -> restricao.alterar(codigo.strip(), descricao.strip(), bloqueiaRemessa));
    }
}
