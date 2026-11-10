package br.com.coronasemijoias.api.cadastros;

import java.time.Clock;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ServicoDeCategorias extends ServicoDeCadastro<Categoria> {

    private final RepositorioDeCategorias categorias;

    public ServicoDeCategorias(RepositorioDeCategorias categorias, Clock relogio) {
        super(categorias, relogio);
        this.categorias = categorias;
    }

    @Transactional(readOnly = true)
    public Page<Categoria> listar(String busca, Integer pagina, Integer tamanho) {
        return categorias.buscar(padraoDeTexto(busca), pagina(pagina, tamanho));
    }

    @Transactional
    public Categoria criar(UUID id, String nome) {
        return gravarNovo(new Categoria(Validacoes.idDoPedido(id), nome.strip()));
    }

    @Transactional
    public Categoria editar(UUID id, Integer versao, String nome) {
        return editarComVersao(id, Validacoes.versaoDoPedido(versao), categoria -> categoria.alterar(nome.strip()));
    }
}
