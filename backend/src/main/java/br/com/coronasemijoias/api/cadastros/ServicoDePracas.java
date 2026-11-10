package br.com.coronasemijoias.api.cadastros;

import java.time.Clock;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ServicoDePracas extends ServicoDeCadastro<Praca> {

    private final RepositorioDePracas pracas;

    public ServicoDePracas(RepositorioDePracas pracas, Clock relogio) {
        super(pracas, relogio);
        this.pracas = pracas;
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
}
