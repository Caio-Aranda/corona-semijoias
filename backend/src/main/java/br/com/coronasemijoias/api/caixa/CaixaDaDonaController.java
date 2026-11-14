package br.com.coronasemijoias.api.caixa;

import br.com.coronasemijoias.api.caixa.ConsultasDoCaixa.Extrato;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import io.swagger.v3.oas.annotations.Operation;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** O caixa, lado da dona (tarefa 7.6): só leitura. Só o perfil ADMIN; o vendedor nunca vê o caixa. */
@RestController
@RequestMapping("/api/dona/caixa")
@ExigePerfil("ADMIN")
public class CaixaDaDonaController {

    private final ConsultasDoCaixa consultas;

    CaixaDaDonaController(ConsultasDoCaixa consultas) {
        this.consultas = consultas;
    }

    @Operation(operationId = "verExtratoDoCaixa", summary = "Extrato do caixa",
            description = "Os lançamentos do caixa (o que entrou dos recebimentos dos vendedores e o que saiu em pagamentos aos "
                    + "vendedores), do mais antigo para o mais novo, com o saldo depois de cada um. Período opcional (de e até, dias "
                    + "de Brasília, inclusive): o saldo anterior é a soma de tudo antes do primeiro dia. Só valores em Real.")
    @GetMapping("/lancamentos")
    public Extrato extrato(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate) {
        return consultas.extrato(de, ate);
    }
}
