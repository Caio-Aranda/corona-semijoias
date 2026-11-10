package br.com.coronasemijoias.api.vendedores;

import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.cadastros.ServicoDeCadastro;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Referências do vendedor (aba "Referências"): várias por vendedor, com exclusão lógica. */
@Service
public class ServicoDeReferencias {

    private final ServicoDeVendedores vendedores;
    private final RepositorioDeReferencias referencias;
    private final Clock relogio;

    public ServicoDeReferencias(ServicoDeVendedores vendedores, RepositorioDeReferencias referencias, Clock relogio) {
        this.vendedores = vendedores;
        this.referencias = referencias;
        this.relogio = relogio;
    }

    @Transactional(readOnly = true)
    public List<ReferenciaDoVendedor.Resposta> listar(UUID vendedorId) {
        vendedores.obter(vendedorId);
        return referencias.findByVendedorIdAndDeletedAtIsNullOrderByCreatedAtAscIdAsc(vendedorId).stream()
                .map(ReferenciaDoVendedor::resposta).toList();
    }

    @Transactional
    public ReferenciaDoVendedor.Resposta criar(UUID vendedorId, ReferenciaDoVendedor.Pedido pedido) {
        vendedores.obter(vendedorId);
        return referencias.saveAndFlush(new ReferenciaDoVendedor(vendedorId, pedido)).resposta();
    }

    @Transactional
    public ReferenciaDoVendedor.Resposta editar(UUID vendedorId, UUID id, ReferenciaDoVendedor.Pedido pedido) {
        int versao = Validacoes.versaoDoPedido(pedido.version());
        ReferenciaDoVendedor referencia = procurar(vendedorId, id);
        if (referencia.getVersion() != versao) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.CADASTRO_ALTERADO);
        }
        referencia.alterar(pedido);
        try {
            return referencias.saveAndFlush(referencia).resposta();
        } catch (OptimisticLockingFailureException e) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.CADASTRO_ALTERADO);
        }
    }

    @Transactional
    public void excluir(UUID vendedorId, UUID id) {
        ReferenciaDoVendedor referencia = procurar(vendedorId, id);
        referencia.marcarComoExcluida(relogio.instant());
        referencias.saveAndFlush(referencia);
    }

    /** A referência precisa ser deste vendedor e não estar excluída; senão, "não encontramos". */
    private ReferenciaDoVendedor procurar(UUID vendedorId, UUID id) {
        vendedores.obter(vendedorId);
        return referencias.findById(id)
                .filter(r -> r.getVendedorId().equals(vendedorId) && !r.estaExcluida())
                .orElseThrow(ServicoDeCadastro::naoEncontrado);
    }
}
