package br.com.coronasemijoias.api.configuracoes;

import br.com.coronasemijoias.api.configuracoes.ServicoDoConsignado.Consignado;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import br.com.coronasemijoias.api.seguranca.UsuarioAutenticado;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A meta, os percentuais e os dias do retorno do consignado (plano acerto-45-dias; especificação §23.13): só a dona. */
@RestController
@RequestMapping("/api/dona/configuracoes/consignado")
@ExigePerfil("ADMIN")
public class ConfiguracaoDoConsignadoController {

    private final ServicoDoConsignado servico;

    ConfiguracaoDoConsignadoController(ServicoDoConsignado servico) {
        this.servico = servico;
    }

    @Operation(operationId = "lerConfiguracaoDoConsignado", summary = "Ver a meta, a comissão e os dias do retorno do consignado",
            description = "Meta em Guarani, percentual abaixo da meta e na meta (de 0 a 40) e dias da saída até o retorno (de 1 a 365).")
    @GetMapping
    public Consignado ler() {
        return servico.ler();
    }

    @Operation(operationId = "alterarConfiguracaoDoConsignado", summary = "Mudar a meta, a comissão e os dias do retorno do consignado",
            description = "Grava os quatro valores. Não muda acerto feito nem Data do retorno já gravada. Cada valor que mudou fica na "
                    + "auditoria (COMISSAO_ALTERADA) com o valor antigo. Fora da faixa, ou abaixo da meta maior que na meta, 400.")
    @PutMapping
    public Consignado alterar(@RequestBody Consignado pedido, @AuthenticationPrincipal UsuarioAutenticado quem) {
        return servico.alterar(pedido, quem.id());
    }
}
