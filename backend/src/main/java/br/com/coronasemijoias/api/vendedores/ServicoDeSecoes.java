package br.com.coronasemijoias.api.vendedores;

import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * As abas de uma linha por vendedor. Ler uma aba nunca gravada devolve tudo vazio e versão 0. Gravar
 * exige a versão que a tela leu (0 se a aba é nova): se outra pessoa gravou antes, 409, em vez de
 * apagar a alteração dela.
 */
@Service
public class ServicoDeSecoes {

    private final ServicoDeVendedores vendedores;

    public ServicoDeSecoes(ServicoDeVendedores vendedores) {
        this.vendedores = vendedores;
    }

    @Transactional(readOnly = true)
    <E extends SecaoDoVendedor, D> D ler(UUID vendedorId, RepositorioDaSecao<E> repositorio,
            Function<E, D> comoDados, D vazia) {
        vendedores.obter(vendedorId);
        return repositorio.findByVendedorId(vendedorId).map(comoDados).orElse(vazia);
    }

    @Transactional
    <E extends SecaoDoVendedor, D> D gravar(UUID vendedorId, Integer versaoLida, RepositorioDaSecao<E> repositorio,
            Function<UUID, E> nova, Consumer<E> aplicar, Function<E, D> comoDados) {
        vendedores.obter(vendedorId);
        int lida = versaoLida == null ? 0 : versaoLida;
        Optional<E> atual = repositorio.findByVendedorId(vendedorId);
        if (lida != atual.map(SecaoDoVendedor::getVersion).orElse(0)) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.CADASTRO_ALTERADO);
        }
        E secao = atual.orElseGet(() -> nova.apply(vendedorId));
        aplicar.accept(secao);
        try {
            return comoDados.apply(repositorio.saveAndFlush(secao));
        } catch (OptimisticLockingFailureException | DataIntegrityViolationException e) {
            // Duas gravações ao mesmo tempo: a segunda recebe o aviso de que outra pessoa gravou.
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.CADASTRO_ALTERADO);
        }
    }
}
