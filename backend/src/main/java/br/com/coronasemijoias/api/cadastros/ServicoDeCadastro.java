package br.com.coronasemijoias.api.cadastros;

import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.comum.persistencia.Excluivel;
import java.time.Clock;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * O que todo cadastro da dona tem em comum: página da lista, busca de um registro que não foi
 * excluído, gravação com a tradução dos erros do banco, edição com a versão que a tela leu e
 * exclusão lógica. As regras de cada cadastro (campos, CPF, CNPJ) ficam nas subclasses.
 *
 * <p>Quem confere a unicidade é o banco (índices parciais, que ignoram o excluído): a conferência
 * de aplicação não impediria dois pedidos ao mesmo tempo.
 */
abstract class ServicoDeCadastro<E extends Excluivel> {

    static final int TAMANHO_PADRAO = 20;
    static final int TAMANHO_MAXIMO = 100;

    private final RepositorioDeCadastro<E> repositorio;
    private final Clock relogio;

    protected ServicoDeCadastro(RepositorioDeCadastro<E> repositorio, Clock relogio) {
        this.repositorio = repositorio;
        this.relogio = relogio;
    }

    protected Pageable pagina(Integer pagina, Integer tamanho) {
        int p = pagina == null ? 0 : pagina;
        int t = tamanho == null ? TAMANHO_PADRAO : tamanho;
        if (p < 0) {
            throw Validacoes.recusar("pagina", "A página não pode ser negativa.");
        }
        if (t < 1 || t > TAMANHO_MAXIMO) {
            throw Validacoes.recusar("tamanho", "Escolha de 1 a " + TAMANHO_MAXIMO + " itens por página.");
        }
        return PageRequest.of(p, t);
    }

    /** Padrão do LIKE para o texto da busca: sem curingas do usuário, minúsculo; vazio acha tudo. */
    protected static String padraoDeTexto(String busca) {
        String limpo = busca == null ? "" : busca.strip().replaceAll("[%_\\\\]", "").toLowerCase(Locale.ROOT);
        return "%" + limpo + "%";
    }

    /**
     * Padrão do LIKE para números de documento (CPF, CNPJ): só letras e números da busca, a partir de
     * 3 caracteres; com menos que isso, um padrão que nunca acha (para "a" não listar todo CNPJ com "A").
     */
    protected static String padraoDeDocumento(String busca) {
        String alfanumerico = busca == null ? "" : busca.toUpperCase(Locale.ROOT).replaceAll("[^0-9A-Z]", "");
        return alfanumerico.length() < 3 ? "-" : "%" + alfanumerico + "%";
    }

    @Transactional(readOnly = true)
    public E obter(UUID id) {
        return procurar(id).orElseThrow(ServicoDeCadastro::naoEncontrado);
    }

    protected Optional<E> procurar(UUID id) {
        return repositorio.findById(id).filter(e -> !e.estaExcluida());
    }

    /** Grava um registro novo. Um id que já existe (mesmo excluído) é recusado, não regravado. */
    protected E gravarNovo(E nova) {
        if (repositorio.existsById(nova.getId())) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.CADASTRO_JA_EXISTE);
        }
        return gravar(nova);
    }

    /**
     * Edita com a versão que a tela leu: se outra pessoa gravou no meio, recusa em vez de apagar a
     * alteração dela.
     */
    protected E editarComVersao(UUID id, int versaoLida, Consumer<E> alteracao) {
        E registro = obter(id);
        if (registro.getVersion() != versaoLida) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.CADASTRO_ALTERADO);
        }
        alteracao.accept(registro);
        return gravar(registro);
    }

    @Transactional
    public void excluir(UUID id) {
        E registro = obter(id);
        registro.marcarComoExcluida(relogio.instant());
        gravar(registro);
    }

    private E gravar(E registro) {
        try {
            return repositorio.saveAndFlush(registro);
        } catch (OptimisticLockingFailureException e) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.CADASTRO_ALTERADO);
        } catch (DataIntegrityViolationException e) {
            throw traduzir(e);
        }
    }

    /** Violação de unicidade ou de sobreposição vira erro em português; qualquer outra segue como erro do servidor. */
    private static RuntimeException traduzir(DataIntegrityViolationException e) {
        StringBuilder textos = new StringBuilder();
        for (Throwable causa = e; causa != null; causa = causa.getCause()) {
            textos.append(causa.getMessage()).append('\n');
        }
        String texto = textos.toString();
        if (texto.contains("sem_sobreposicao")) {
            return new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.SEMANA_SOBREPOSTA);
        }
        if (texto.contains("_unic") || texto.contains("duplicate key")) {
            return new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.CADASTRO_JA_EXISTE);
        }
        return e;
    }

    static ErroDaApi naoEncontrado() {
        return new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.CADASTRO_NAO_ENCONTRADO);
    }
}
