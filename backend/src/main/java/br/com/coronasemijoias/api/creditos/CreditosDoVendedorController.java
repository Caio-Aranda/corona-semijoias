package br.com.coronasemijoias.api.creditos;

import br.com.coronasemijoias.api.creditos.ConsultasDeCreditos.CreditoDoCliente;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import br.com.coronasemijoias.api.seguranca.UsuarioAutenticado;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Créditos do cliente do vendedor (tarefa 6.5). Só o perfil VENDEDOR e só os créditos dele (os que ele gerou ou que a
 * dona transferiu para ele): o vendedor vem do token. O uso é pela venda (forma CREDITO).
 */
@RestController
@RequestMapping("/api/vendedor/creditos")
@ExigePerfil("VENDEDOR")
public class CreditosDoVendedorController {

    private final ConsultasDeCreditos consultas;

    CreditosDoVendedorController(ConsultasDeCreditos consultas) {
        this.consultas = consultas;
    }

    @Operation(operationId = "listarCreditosDoVendedor", summary = "Créditos dos meus clientes",
            description = "Só os créditos do vendedor logado, do mais novo para o mais antigo, com saldo, origem e usos. Filtros "
                    + "opcionais por cliente e situação (ABERTO, UTILIZADO ou CANCELADO).")
    @GetMapping
    public List<CreditoDoCliente> listar(@RequestParam(required = false) UUID clienteId,
            @RequestParam(required = false) String situacao, @AuthenticationPrincipal UsuarioAutenticado quem) {
        return consultas.doVendedor(quem.vendedorId(), clienteId, situacao);
    }

    @Operation(operationId = "verCreditoDoVendedor", summary = "Ver um crédito de cliente")
    @GetMapping("/{id}")
    public CreditoDoCliente ver(@PathVariable UUID id, @AuthenticationPrincipal UsuarioAutenticado quem) {
        return consultas.ver(id, quem.vendedorId());
    }
}
