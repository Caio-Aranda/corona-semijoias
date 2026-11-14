package br.com.coronasemijoias.api.contasreceber;

import br.com.coronasemijoias.api.contasreceber.ConsultasDeRecebimentosDoVendedor.RecebimentoDoVendedor;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import br.com.coronasemijoias.api.seguranca.UsuarioAutenticado;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Recebimentos do vendedor, lado do vendedor (tarefa 7.6): só leitura e só os dele. O vendedor não registra nem estorna
 * recebimento (tudo que é dinheiro é da dona) e nunca vê o caixa, quem registrou ou o motivo do estorno.
 */
@RestController
@RequestMapping("/api/vendedor")
@ExigePerfil("VENDEDOR")
public class RecebimentosDoVendedorDoVendedorController {

    private final ConsultasDeRecebimentosDoVendedor consultas;

    RecebimentosDoVendedorDoVendedorController(ConsultasDeRecebimentosDoVendedor consultas) {
        this.consultas = consultas;
    }

    @Operation(operationId = "listarMeusRecebimentos", summary = "O que paguei à loja",
            description = "Os recebimentos do vendedor logado (dinheiro, Transferência (PIX) e boleto pago), do mais novo para o mais "
                    + "antigo, e os estornos deles. Só valores em Real.")
    @GetMapping("/recebimentos")
    public List<RecebimentoDoVendedor> listar(@AuthenticationPrincipal UsuarioAutenticado quem) {
        return consultas.doVendedor(quem.vendedorId());
    }
}
