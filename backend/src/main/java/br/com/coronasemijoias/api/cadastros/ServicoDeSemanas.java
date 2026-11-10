package br.com.coronasemijoias.api.cadastros;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Semanas: número único e datas sem sobreposição (o banco garante as duas coisas, também entre pedidos simultâneos). */
@Service
public class ServicoDeSemanas extends ServicoDeCadastro<Semana> {

    private final RepositorioDeSemanas semanas;

    public ServicoDeSemanas(RepositorioDeSemanas semanas, Clock relogio) {
        super(semanas, relogio);
        this.semanas = semanas;
    }

    @Transactional(readOnly = true)
    public Page<Semana> listar(String busca, Integer pagina, Integer tamanho) {
        return semanas.buscar(padraoDeTexto(busca), pagina(pagina, tamanho));
    }

    @Transactional
    public Semana criar(UUID id, int numero, LocalDate inicio, LocalDate fim, SituacaoDaSemana situacao) {
        conferirDatas(inicio, fim);
        return gravarNovo(new Semana(Validacoes.idDoPedido(id), numero, inicio, fim,
                situacao == null ? SituacaoDaSemana.ABERTA : situacao));
    }

    @Transactional
    public Semana editar(UUID id, Integer versao, int numero, LocalDate inicio, LocalDate fim,
            SituacaoDaSemana situacao) {
        conferirDatas(inicio, fim);
        return editarComVersao(id, Validacoes.versaoDoPedido(versao), semana -> semana.alterar(numero, inicio, fim,
                situacao == null ? semana.getSituacao() : situacao));
    }

    private static void conferirDatas(LocalDate inicio, LocalDate fim) {
        if (fim.isBefore(inicio)) {
            throw Validacoes.recusar("fim", "O último dia não pode ser antes do primeiro dia.");
        }
    }
}
