package br.com.coronasemijoias.api.acertos;

import br.com.coronasemijoias.api.acertos.ServicoDaComissao.Comissao;
import br.com.coronasemijoias.api.acertos.ServicoDaComissao.Previa;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import br.com.coronasemijoias.api.seguranca.UsuarioAutenticado;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A comissão por meta (tarefa 7.5; ADR-0012, item 5): a configuração é da dona; a prévia é do vendedor, só a dele. */
public final class ComissaoController {

    private ComissaoController() {
    }

    @RestController
    @RequestMapping("/api/dona/configuracoes/comissao")
    @ExigePerfil("ADMIN")
    public static class DaDona {

        private final ServicoDaComissao servico;

        DaDona(ServicoDaComissao servico) {
            this.servico = servico;
        }

        @Operation(operationId = "lerComissao", summary = "Ver a meta e os percentuais da comissão",
                description = "Meta da semana em Guarani e os dois percentuais (abaixo da meta e na meta), iguais para todos os vendedores.")
        @GetMapping
        public Comissao ler() {
            return servico.ler();
        }

        @Operation(operationId = "alterarComissao", summary = "Mudar a meta e os percentuais da comissão",
                description = "Grava os três valores. Vale para o próximo fechamento e nunca muda acerto fechado. Cada valor que mudou fica "
                        + "na auditoria com o valor antigo. Fora da faixa, ou abaixo da meta maior que na meta, 400.")
        @PutMapping
        public Comissao alterar(@RequestBody Comissao pedido, @AuthenticationPrincipal UsuarioAutenticado quem) {
            return servico.alterar(pedido, quem.id());
        }
    }

    @RestController
    @RequestMapping("/api/vendedor/comissao")
    @ExigePerfil("VENDEDOR")
    public static class DoVendedor {

        private final ServicoDaComissao servico;

        DoVendedor(ServicoDaComissao servico) {
            this.servico = servico;
        }

        @Operation(operationId = "previaDaMinhaComissao", summary = "Prévia da minha comissão",
                description = "O vendido da semana em Guarani, o percentual que valeria hoje, quanto falta para a meta (em Guarani e em Real, "
                        + "pela cotação mais recente) e a comissão prevista. Só do vendedor logado; nada é gravado.")
        @GetMapping("/previa")
        public Previa previa(@AuthenticationPrincipal UsuarioAutenticado quem) {
            return servico.previa(quem.vendedorId());
        }
    }
}
